package com.findworks.discovery;

import com.findworks.shaping.ShapingRepository;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
final class DiscoveryController {

    private final DiscoveryRepository repository;
    private final ShapingRepository shaping;

    DiscoveryController(DiscoveryRepository repository, ShapingRepository shaping) {
        this.repository = repository;
        this.shaping = shaping;
    }

    @GetMapping("/discoveries")
    String discoveries(Principal principal, Model model) {
        model.addAttribute("discoveries", repository.all(principal.getName()));
        return "discoveries";
    }

    @GetMapping("/discoveries/new")
    String newDiscovery() {
        return "new-discovery";
    }

    @PostMapping("/discoveries")
    String create(Principal principal, @RequestParam String title, @RequestParam String objective) {
        var id = repository.create(principal.getName(), DiscoveryDraft.from(title, objective));
        return "redirect:/discoveries/" + id;
    }

    @GetMapping("/discoveries/{id}")
    String discovery(Principal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("discovery", repository.get(principal.getName(), id));
        model.addAttribute("shaping", shaping.view(principal.getName(), id));
        return "discovery";
    }

    @PostMapping("/discoveries/{id}/shaping")
    String shape(Principal principal, @PathVariable UUID id, @RequestParam String content) {
        shaping.submit(principal.getName(), id, content);
        return "redirect:/discoveries/" + id;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalid(IllegalArgumentException error, Model model) {
        model.addAttribute("message", error.getMessage());
        return "error";
    }
}
