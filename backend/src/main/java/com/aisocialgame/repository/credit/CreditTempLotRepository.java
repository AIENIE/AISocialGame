package com.aisocialgame.repository.credit;
import com.aisocialgame.model.credit.CreditTempLot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CreditTempLotRepository extends JpaRepository<CreditTempLot,Long> {
    List<CreditTempLot> findByUserIdAndProjectKeyAndRemainingGreaterThan(long userId,String projectKey,long remaining);
}
