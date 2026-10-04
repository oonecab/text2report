package com.text2report.domain.vector.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.text2report.domain.vector.entity.MetaEmbedding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface MetaEmbeddingMapper extends BaseMapper<MetaEmbedding> {

    int upsertEmbedding(@Param("sourceType") String sourceType,
                        @Param("sourceId") Integer sourceId,
                        @Param("contentText") String contentText,
                        @Param("embedding") float[] embedding,
                        @Param("modelVersion") String modelVersion);

    List<MetaEmbedding> searchSimilar(@Param("queryVector") float[] queryVector,
                                      @Param("topK") Integer topK,
                                      @Param("sourceType") String sourceType);

    int deleteBySource(@Param("sourceType") String sourceType, @Param("sourceId") Integer sourceId);

    List<Map<String, Object>> countByType();
}
