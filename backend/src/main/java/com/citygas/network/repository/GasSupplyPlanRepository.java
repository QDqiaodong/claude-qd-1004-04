package com.citygas.network.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.GasSupplyPlan;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface GasSupplyPlanRepository extends JpaRepository<GasSupplyPlan, Long> {

    @Query("select p from GasSupplyPlan p where p.id = :id")
    Optional<GasSupplyPlan> findScopedById(@Param("id") Long id);

    Optional<GasSupplyPlan> findFirstByPlanNoOrderByPlanVersionDesc(String planNo);

    @Query("select coalesce(max(p.planVersion), 0) from GasSupplyPlan p where p.planNo = :planNo")
    Integer maxVersion(@Param("planNo") String planNo);

    @Query("""
            select p from GasSupplyPlan p
            where (:planDate is null or p.planDate = :planDate)
              and (:status is null or p.status = :status)
              and (:keyword is null
                   or p.planNo like concat('%', :keyword, '%')
                   or p.createdBy like concat('%', :keyword, '%'))
            order by p.planDate desc, p.planNo asc, p.planVersion desc, p.id desc
            """)
    Page<GasSupplyPlan> search(@Param("planDate") LocalDate planDate,
                               @Param("status") String status,
                               @Param("keyword") String keyword,
                               Pageable pageable);

    @Query("""
            select p from GasSupplyPlan p
            where p.planNo = :planNo
            order by p.planVersion asc, p.id asc
            """)
    List<GasSupplyPlan> findByPlanNoOrderByVersion(@Param("planNo") String planNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select p from GasSupplyPlan p where p.id = :id")
    Optional<GasSupplyPlan> findByIdForUpdate(@Param("id") Long id);
}
