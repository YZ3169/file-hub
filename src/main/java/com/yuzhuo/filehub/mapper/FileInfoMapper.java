package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.FileInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}
