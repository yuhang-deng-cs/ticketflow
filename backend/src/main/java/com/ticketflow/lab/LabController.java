package com.ticketflow.lab;

import com.ticketflow.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lab")
@RequiredArgsConstructor
@Tag(name = "Oversell Lab", description = "Race N buyers against one inventory strategy and see who oversells")
public class LabController {

    private final LabService labService;

    @GetMapping("/strategies")
    @Operation(summary = "List inventory strategies, whether they are available, and which one the booking flow uses")
    public ResponseEntity<ApiResponse<LabDto.Catalog>> strategies() {
        return ResponseEntity.ok(ApiResponse.ok(labService.catalog()));
    }

    @PostMapping("/runs")
    @Operation(summary = "Run one strategy against a fresh tier with N simultaneous buyers")
    public ResponseEntity<ApiResponse<LabDto.RunResult>> run(@Valid @RequestBody LabDto.RunRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(labService.run(request)));
    }
}
