package com.citygas.network.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.GasPlanHistory;

public interface GasPlanHistoryRepository extends JpaRepository<GasPlanHistory, Long> {

    @Query("""
            select h from GasPlanHistory h
            where h.planVersionId in :versionIds
            order by h.createdAt asc, h.id asc
            """)
    List<GasPlanHistory> findByVersionIds(@Param("versionIds") List<Long> versionIds);

    @Query("select h from GasPlanHistory h where h.planVersionId = :versionId order by h.createdAt asc, h.id asc")
    List<GasPlanHistory> findByVersion(@Param("versionId") Long versionId);
}
