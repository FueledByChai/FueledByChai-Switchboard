package com.fueledbychai.dashboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardPageController {

    @GetMapping("/")
    public String dashboard() {
        return "dashboard";
    }
}
