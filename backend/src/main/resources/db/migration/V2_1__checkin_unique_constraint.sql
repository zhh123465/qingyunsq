-- Bug fix 1.11: 防止并发打卡产生重复记录
ALTER TABLE checkin_records ADD UNIQUE INDEX uk_challenge_user_date (challenge_id, user_id, checkin_date);
