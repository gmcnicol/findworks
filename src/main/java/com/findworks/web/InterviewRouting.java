package com.findworks.web;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Keep existing interview actions session-scoped, including redirects and streamed fragments. */
@Configuration
class InterviewRouting implements WebMvcConfigurer {
    static String base(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> map && map.get("sessionId") instanceof String id) {
            try { return "/interview/" + UUID.fromString(id); } catch (IllegalArgumentException ignored) { }
        }
        return "/interview";
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                                             ModelAndView view) {
                if (view == null) return;
                String base = base(request);
                if ("redirect:/interview".equals(view.getViewName())) view.setViewName("redirect:" + base);
                else if (view.getViewName() == null || !view.getViewName().startsWith("redirect:")) view.addObject("interviewBase", base);
                response.setHeader("Cache-Control", "no-store");
            }
        }).addPathPatterns("/interview/**");
    }
}
