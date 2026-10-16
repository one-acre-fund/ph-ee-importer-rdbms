package hu.dpc.phee.operator.entity.callactivity;

import org.springframework.data.repository.CrudRepository;

public interface CallActivityLinkRepository extends CrudRepository<CallActivityLink, Long> {
    CallActivityLink findByChildInstanceKey(Long childInstanceKey);
}
