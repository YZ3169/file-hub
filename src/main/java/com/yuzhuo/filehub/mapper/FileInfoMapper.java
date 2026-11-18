package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.FileInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface FileInfoMapper extends BaseMapper<FileInfo> {

    /**
     * 秒传查询：同 hash + 同大小且已完成的文件才算命中。
     */
    @Select("SELECT * FROM file_info WHERE file_hash = #{hash} AND file_size = #{size} "
            + "AND status = 1 ORDER BY id LIMIT 1")
    FileInfo findDoneByHashAndSize(String hash, long size);

    /**
     * 数一下还有几行逻辑文件引用同一个物理文件——秒传会让多行共享一个 storage_path，
     * 只有引用数为 1 时才能真删磁盘文件。
     */
    @Select("SELECT COUNT(*) FROM file_info WHERE storage_path = #{path} AND status = 1")
    int countByStoragePath(String path);

    /**
     * 合并完成的落库动作。带上 status = 0 条件，天然幂等：
     * 两个请求同时点「完成」时，只有一个能把 0 改成 1，另一个影响 0 行、直接跳过后续清理。
     */
    @Update("UPDATE file_info SET storage_path = #{path}, status = 1, update_time = NOW() "
            + "WHERE file_id = #{fileId} AND status = 0")
    int markDone(String fileId, String path);

    /**
     * 每上传成功一个分片就刷新一次 update_time。
     * 清理任务靠它区分「用户慢慢传」和「项目已废弃」——不能拿 create_time 当判据。
     */
    @Update("UPDATE file_info SET update_time = NOW() WHERE file_id = #{fileId} AND status = 0")
    int touchUploading(String fileId);

    /** 长时间没有任何分片落盘的上传任务，交给定时任务回收 */
    @Select("SELECT * FROM file_info WHERE status = 0 "
            + "AND update_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE) "
            + "ORDER BY id LIMIT 200")
    List<FileInfo> findExpiredUploading(@Param("minutes") int minutes);

    /**
     * 清理时的条件删除：从「查到过期」到「拿锁后删除」之间，分片可能刚刷新过 update_time。
     * 只有仍然是上传中、且仍然过期的行才删；返回 0 表示任务已复活或已被处理。
     */
    @Update("DELETE FROM file_info WHERE id = #{id} AND status = 0 "
            + "AND update_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    int deleteExpiredById(@Param("id") Long id, @Param("minutes") int minutes);

    /** 仍在进行中的任务 id，用于判断 chunks/ 下哪些目录是孤儿 */
    @Select("SELECT file_id FROM file_info WHERE status = 0")
    List<String> findUploadingFileIds();

    @Select("SELECT COUNT(*) FROM file_info WHERE status = 1")
    long countDone();

    @Select("SELECT COUNT(*) FROM file_info WHERE status = 0")
    long countUploading();

    /** 逻辑字节数：每个逻辑文件都算一遍 */
    @Select("SELECT COALESCE(SUM(file_size), 0) FROM file_info WHERE status = 1")
    long sumLogicalBytes();

    /** 物理字节数：按 storage_path 去重，秒传共享的物理文件只算一次 */
    @Select("SELECT COALESCE(SUM(sz), 0) FROM ("
            + "  SELECT MAX(file_size) AS sz FROM file_info WHERE status = 1 GROUP BY storage_path"
            + ") t")
    long sumPhysicalBytes();
}
