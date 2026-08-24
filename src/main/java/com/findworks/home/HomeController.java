package com.findworks.home;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnExpression("'${findworks.process-role:local}' == 'local' || '${findworks.process-role:local}' == 'web'")
final class HomeController {

    @GetMapping("/")
    String home() {
        return "home";
    }
}
