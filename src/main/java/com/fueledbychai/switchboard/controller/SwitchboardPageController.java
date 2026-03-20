package com.fueledbychai.switchboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SwitchboardPageController {

    @GetMapping("/")
    public String switchboard() {
        return "switchboard";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
