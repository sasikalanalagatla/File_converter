package com.iLovePdf.conversion.controller;

import com.iLovePdf.conversion.service.FilesStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
public class PdfController {

    private static final Logger logger = LoggerFactory.getLogger(PdfController.class);

    @Autowired
    private FilesStorageService storageService;

    @GetMapping("/")
    public String uploadForm() {
        logger.info("Rendering upload form");
        return "upload";
    }

    @PostMapping("/convert")
    public ResponseEntity<byte[]> convert(
            @RequestParam("file") MultipartFile[] files,
            @RequestParam(value = "format", required = false) String format,
            RedirectAttributes redirectAttributes) {

        logger.info("Received convert request with format: {}", format);

        try {
            Map<String, Object> result = storageService.processFiles(files, format);

            byte[] bytes = (byte[]) result.get("bytes");
            String filename = (String) result.get("filename");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .body(bytes);

        } catch (IllegalArgumentException e) {
            logger.warn("Validation failed: {}", e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return ResponseEntity.badRequest().body(null);

        } catch (Exception e) {
            logger.error("Conversion failed", e);
            redirectAttributes.addFlashAttribute("error", "Error during processing: " + e.getMessage());
            return ResponseEntity.status(500).body(null);
        }
    }
}