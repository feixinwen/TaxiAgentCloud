package com.fancy.taxiagent.auth.id;

/**
 * Auth Service 生成全局业务 ID 的抽象。
 *
 * <p>注册编排只依赖本接口，不直接依赖具体雪花算法，便于测试和未来替换节点分配方案。</p>
 */
public interface IdGenerator {

    /**
     * 生成一个正数且在当前节点生命周期内单调递增的全局唯一 ID。
     *
     * @return 全局业务 ID
     */
    long nextId();
}
