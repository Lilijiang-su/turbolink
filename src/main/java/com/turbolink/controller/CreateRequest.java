package com.turbolink.controller;

import lombok.Data;

@Data
public class CreateRequest {
    private String longUrl;

    /** 自定义短码（可选，4-16 位字母数字） */
    private String customCode;
}
