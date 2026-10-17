package com.findworks.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/** Keeps browser failures recoverable instead of exposing the framework fallback page. */
@Controller
final class AppErrorController implements ErrorController {

    @RequestMapping("/error")
    String error(HttpServletRequest request, HttpServletResponse response, Model model) {
        var value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        var status = value instanceof Number number ? number.intValue() : 500;
        response.setStatus(status);
        model.addAttribute("status", status);
        model.addAttribute("title", switch (status) {
            case 403 -> "Access is no longer available";
            case 404 -> "We couldn’t find that page";
            case 409 -> "That action is already out of date";
            case 422 -> "That action needs attention";
            default -> "We couldn’t complete that action";
        });
        model.addAttribute("message", switch (status) {
            case 409 -> "Your work may already have moved on. Return to your saved work to see its current state.";
            case 422 -> "Return to the current page, review what is needed, and try again.";
            default -> "Your accepted work is safe. Return to your saved work and try again.";
        });
        return "error";
    }
}
