package hu.dpc.phee.operator.entity.callactivity;

import hu.dpc.phee.operator.entity.parent.AbstractPersistableCustom;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/**
 * Child process instance (started through a call activity) to parent transfer instance.
 * Written when the child activates and read whenever the in-memory map misses, so child
 * variables keep targeting the parent transfer after a rebalance, a cache clear or a pod loss.
 */
@Entity
@Table(name = "call_activity_links", uniqueConstraints = @UniqueConstraint(name = "uk_call_activity_links_child", columnNames = {"CHILD_INSTANCE_KEY"}))
public class CallActivityLink extends AbstractPersistableCustom<Long> {

    @Column(name = "CHILD_INSTANCE_KEY", nullable = false)
    private Long childInstanceKey;

    @Column(name = "PARENT_INSTANCE_KEY", nullable = false)
    private Long parentInstanceKey;

    public CallActivityLink() {
    }

    public CallActivityLink(Long childInstanceKey, Long parentInstanceKey) {
        this.childInstanceKey = childInstanceKey;
        this.parentInstanceKey = parentInstanceKey;
    }

    public Long getChildInstanceKey() {
        return childInstanceKey;
    }

    public void setChildInstanceKey(Long childInstanceKey) {
        this.childInstanceKey = childInstanceKey;
    }

    public Long getParentInstanceKey() {
        return parentInstanceKey;
    }

    public void setParentInstanceKey(Long parentInstanceKey) {
        this.parentInstanceKey = parentInstanceKey;
    }
}
