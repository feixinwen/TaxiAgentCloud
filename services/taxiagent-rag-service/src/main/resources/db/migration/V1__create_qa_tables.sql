CREATE TABLE `sys_qa_info` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `group_id` BIGINT NOT NULL COMMENT '答案组ID(雪花)',
    `answer` TEXT NOT NULL COMMENT '标准答案',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答答案组';

CREATE TABLE `sys_qa_es` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `elastic_id` BIGINT NOT NULL COMMENT 'ES文档ID(雪花)',
    `group_id` BIGINT NOT NULL COMMENT '所属答案组ID',
    `question` VARCHAR(255) NOT NULL COMMENT '问题文本',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问题到ES文档映射';
