package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.FileChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FileChunkMapper extends BaseMapper<FileChunk> {

    /**
     * 已经落盘的分片序号。init 时回给前端，就是「断点续传」的全部秘密：
     * 前端跳过这些序号，只补缺失的。
     */
    @Select("SELECT chunk_index FROM file_chunk WHERE file_id = #{fileId} ORDER BY chunk_index")
    List<Integer> listUploadedIndexes(@Param("fileId") String fileId);

    /**
     * 分片元数据（序号 + 摘要）。合并时逐片复算 SHA-256 与它比对，
     * 能发现"数据库正常、磁盘被悄悄改坏"的分片。
     */
    @Select("SELECT chunk_index, chunk_hash FROM file_chunk WHERE file_id = #{fileId}")
    List<FileChunk> listChunkMeta(@Param("fileId") String fileId);

    /**
     * 分片元数据的 upsert。重传同一分片时覆盖大小和摘要，
     * 这样"文件已经原子覆盖成功、数据库却没更新"的窗口不会留下过期摘要。
     */
    @Insert("INSERT INTO file_chunk(file_id, chunk_index, chunk_size, chunk_hash, create_time) "
            + "VALUES(#{fileId}, #{index}, #{size}, #{hash}, NOW()) "
            + "ON DUPLICATE KEY UPDATE chunk_size = VALUES(chunk_size), chunk_hash = VALUES(chunk_hash)")
    int upsertChunk(@Param("fileId") String fileId,
                    @Param("index") int index,
                    @Param("size") long size,
                    @Param("hash") String hash);

    /** 合并阶段发现损坏分片时，把它的元数据删掉，让它出现在下一次 status 的缺失列表里 */
    @Delete("DELETE FROM file_chunk WHERE file_id = #{fileId} AND chunk_index = #{index}")
    int deleteChunk(@Param("fileId") String fileId, @Param("index") int index);

    @Delete("DELETE FROM file_chunk WHERE file_id = #{fileId}")
    int deleteByFileId(@Param("fileId") String fileId);
}
