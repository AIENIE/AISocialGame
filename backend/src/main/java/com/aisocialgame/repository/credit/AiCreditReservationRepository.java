package com.aisocialgame.repository.credit;
import com.aisocialgame.model.credit.AiCreditReservation;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface AiCreditReservationRepository extends JpaRepository<AiCreditReservation,String> {
    interface LabTotals {
        long getSpentPoints(); long getPendingCalls(); long getTotalCalls();
        java.math.BigDecimal getActualCost();
    }
    @Query("""
        select coalesce(sum(r.billedPoints),0) as spentPoints,
          coalesce(sum(case when r.state in ('PENDING','USAGE_UNKNOWN','HELD') then 1 else 0 end),0) as pendingCalls,
          count(r) as totalCalls, coalesce(sum(r.billedCost),0) as actualCost
        from AiCreditReservation r where r.labExperimentId=:experimentId
        """)
    LabTotals labTotals(@Param("experimentId") String experimentId);
    Optional<AiCreditReservation> findByIdentityHash(String identityHash);
    List<AiCreditReservation> findByLabMatchIdOrderByUpdatedAtAsc(String matchId);
    List<AiCreditReservation> findByUserIdAndProjectKeyAndState(long userId, String projectKey, String state);
    boolean existsByUserIdAndProjectKey(long userId, String projectKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from AiCreditReservation r where r.id=:id")
    Optional<AiCreditReservation> lock(@Param("id") String id);
    List<AiCreditReservation> findByStateInOrderByUpdatedAtAsc(List<String> states,Pageable page);
    List<AiCreditReservation> findByStateOrderByUpdatedAtAsc(String state,Pageable page);
}
