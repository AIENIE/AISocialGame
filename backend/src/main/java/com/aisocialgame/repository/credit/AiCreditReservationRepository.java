package com.aisocialgame.repository.credit;
import com.aisocialgame.model.credit.AiCreditReservation;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface AiCreditReservationRepository extends JpaRepository<AiCreditReservation,String> {
    Optional<AiCreditReservation> findByIdentityHash(String identityHash);
    List<AiCreditReservation> findByUserIdAndProjectKeyAndState(long userId, String projectKey, String state);
    boolean existsByUserIdAndProjectKey(long userId, String projectKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from AiCreditReservation r where r.id=:id")
    Optional<AiCreditReservation> lock(@Param("id") String id);
    List<AiCreditReservation> findByStateOrderByUpdatedAtAsc(String state,Pageable page);
}
