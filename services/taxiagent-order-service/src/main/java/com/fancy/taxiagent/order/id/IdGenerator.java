package com.fancy.taxiagent.order.id;

/**
 * Order Service 生成全局业务 ID 的抽象。
 */
public interface IdGenerator {

    /**
     * 生成一个正数且在当前节点生命周期内单调递增的全局唯一 ID。
     *
     * @return 全局业务 ID
     */
    long nextId();
}
