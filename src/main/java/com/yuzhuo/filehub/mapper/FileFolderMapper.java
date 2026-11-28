package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.FileFolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FileFolderMapper extends BaseMapper<FileFolder> {

    @Select("SELECT * FROM file_folder WHERE owner_id = #{ownerId} AND folder_path_hash = #{hash} LIMIT 1")
    FileFolder findByOwnerAndHash(@Param("ownerId") Long ownerId, @Param("hash") String hash);

    @Select("SELECT * FROM file_folder WHERE owner_id = #{ownerId} ORDER BY folder_path ASC")
    List<FileFolder> listByOwner(@Param("ownerId") Long ownerId);
}
