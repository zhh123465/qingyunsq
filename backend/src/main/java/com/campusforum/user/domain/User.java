package com.campusforum.user.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campusforum.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("users")
public class User extends BaseEntity {

    private String studentNo;
    private String email;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private String wechatOpenid;
    private String wechatUnionid;
    private String qqOpenid;
    private String githubId;
    private String profileCoverUrl;
    private String bio;
    private String college;
    private String major;
    private String grade;
    private String role;
    private Integer status;
    private LocalDateTime lastLoginAt;
    private String resetToken;
    private LocalDateTime resetTokenExpires;
    private String muteSettings;
    private String tagSubscriptions;
    private Integer welcomeKbSeeded;
}
