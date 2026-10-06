package com.citygas.network.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.GasPlanQuota;

public interface GasPlanQuotaRepository extends JpaRepository<GasPlanQuota, Long> {

    @Query("select q from GasPlanQuota q where q.planVersionId = :versionId order by q.stationType desc, q.stationCode asc, q.id asc")
    List<GasPlanQuota> findByVersion(@Param("versionId") Long versionId);

    @Modifying
    @Query("delete from GasPlanQuota q where q.planVersionId = :versionId")
    void deleteByVersion(@Param("versionId") Long versionId);
}
