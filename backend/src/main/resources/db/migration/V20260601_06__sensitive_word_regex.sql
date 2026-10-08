-- ============================================================
-- 任务 T8.5（bugfix.md 漏洞 27）：敏感词支持正则匹配
-- ============================================================
-- 背景：早期 sensitive_words 表仅按字面量 String.contains 比较，
-- 攻击者可借全角 / 零宽 / 大小写绕过；同时缺乏正则匹配能力，
-- 难以表达"以 X 开头" / "含 X 后跟数字"等风控规则。
--
-- 改造：
--   - 新增 is_regex 字段（TINYINT 0/1，默认 0）；
--   - is_regex=0：内容 / 词条经 NFKC + 零宽剥离 + 全角转半角 + 小写归一化后 contains 比较；
--   - is_regex=1：归一化后用 java.util.regex.Pattern.find 匹配。管理员录入正则需先在测试用例上确认匹配范围。
-- ============================================================

ALTER TABLE sensitive_words
    ADD COLUMN is_regex TINYINT NOT NULL DEFAULT 0
        COMMENT '0=普通词 1=正则表达式（管理员需测试通过后启用）'
    AFTER level;
