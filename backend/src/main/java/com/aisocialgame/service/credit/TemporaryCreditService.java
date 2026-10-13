package com.aisocialgame.service.credit;

import com.aisocialgame.model.credit.CreditAccount;
import com.aisocialgame.model.credit.CreditTempLot;
import com.aisocialgame.repository.credit.CreditTempLotRepository;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Mutations require the caller to hold the account row lock in the same transaction. */
@Service
public class TemporaryCreditService {
    public record Portion(long amount, Instant expiresAt) {}
    private final CreditTempLotRepository lots;
    public TemporaryCreditService(CreditTempLotRepository lots) { this.lots=lots; }
    public List<CreditTempLot> available(CreditAccount account, Instant now) {
        return lots.findByUserIdAndProjectKeyAndRemainingGreaterThan(account.getUserId(),account.getProjectKey(),0)
            .stream().filter(l -> l.expiresAt==null || l.expiresAt.isAfter(now))
            .sorted(Comparator.comparing(l -> l.expiresAt, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }
    public long returnedBalance(CreditAccount account) {
        return available(account,Instant.now()).stream().mapToLong(l->l.remaining).reduce(0,Math::addExact);
    }
    public Instant earliestExpiry(CreditAccount account,Instant bucketExpiry) {
        return available(account,Instant.now()).stream().map(l->l.expiresAt).filter(Objects::nonNull)
            .reduce(bucketExpiry,(a,b)->a==null || b.isBefore(a) ? b : a);
    }
    public long balance(CreditAccount account,Instant now) {
        long bucket=account.getTempExpiresAt()==null || account.getTempExpiresAt().isAfter(now) ? account.getTempBalance() : 0;
        return Math.addExact(bucket,available(account,now).stream().mapToLong(l->l.remaining).reduce(0,Math::addExact));
    }
    public List<Portion> take(CreditAccount account,long amount,Instant now) {
        if(amount<0 || balance(account,now)<amount) throw new IllegalArgumentException("Insufficient temporary credits");
        List<CreditTempLot> sources=new ArrayList<>(available(account,now));
        CreditTempLot bucket=new CreditTempLot(); bucket.remaining=account.getTempExpiresAt()==null || account.getTempExpiresAt().isAfter(now) ? account.getTempBalance() : 0;
        bucket.expiresAt=account.getTempExpiresAt(); sources.add(bucket);
        sources.sort(Comparator.comparing(l->l.expiresAt,Comparator.nullsLast(Comparator.naturalOrder())));
        List<Portion> portions=new ArrayList<>();
        long remaining=amount;
        for(var source:sources) {
            long debit=Math.min(remaining,source.remaining);
            if(debit==0) continue;
            portions.add(new Portion(debit,source.expiresAt)); source.remaining-=debit; remaining-=debit;
            if(source==bucket) account.setTempBalance(account.getTempBalance()-debit); else lots.save(source);
        }
        return portions;
    }
    public void refund(CreditAccount account,long amount,Instant expiresAt,Instant now) {
        if(amount<=0 || (expiresAt!=null && !expiresAt.isAfter(now))) return;
        CreditTempLot lot=new CreditTempLot(); lot.userId=account.getUserId(); lot.projectKey=account.getProjectKey();
        lot.remaining=amount; lot.expiresAt=expiresAt; lots.save(lot);
    }
}
