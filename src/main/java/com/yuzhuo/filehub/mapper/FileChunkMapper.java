package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.FileChunk;
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
}
