package com.iLovePdf.conversion.controller;

import com.iLovePdf.conversion.service.FilesStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Controller
public class PdfController {

    @Autowired
    FilesStorageService storageService;

    @GetMapping("/")
    public String uploadForm() {
        return "upload";
    }

    @PostMapping("/convert")
    public Object convert(
            @RequestParam("file") MultipartFile[] files,
            @RequestParam(value = "format", required = false) String format,
            RedirectAttributes redirectAttributes) {

        if (files == null || files.length == 0) {
            redirectAttributes.addFlashAttribute("error", "Please upload at least one file!");
            return "redirect:/";
        }

        List<String> savedPaths = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String filename = file.getOriginalFilename();
            if (filename == null || filename.trim().isEmpty()) {
                continue;
            }

            // Check both extension and content type for better security
            boolean isPdf = filename.toLowerCase().endsWith(".pdf") &&
                    ("application/pdf".equals(file.getContentType()) ||
                            file.getContentType() == null); // some browsers send null

            if (!isPdf) {
                redirectAttributes.addFlashAttribute("error", "Only PDF files are allowed! Invalid file: " + filename);
                cleanupSavedFiles(savedPaths); // clean up any partially saved files
                return "redirect:/";
            }

            try {
                String savedPath = storageService.save(file);
                savedPaths.add(savedPath);
            } catch (Exception e) {
                redirectAttributes.addFlashAttribute("error", "Failed to save file: " + filename);
                cleanupSavedFiles(savedPaths);
                return "redirect:/";
            }
        }

        if (savedPaths.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "No valid PDF files were uploaded!");
            return "redirect:/";
        }

        File resultFile = null;

        try {
            if (savedPaths.size() > 1) {
                // Multiple files → merge
                resultFile = storageService.mergePdfs(savedPaths);
            } else {
                // Single file → conversion or no-op merge
                String pdfPath = savedPaths.get(0);

                if (format == null || format.trim().isEmpty()) {
                    redirectAttributes.addFlashAttribute("error", "Please select a format for single PDF!");
                    return "redirect:/";
                }

                switch (format.toLowerCase()) {
                    case "word":
                        resultFile = storageService.convertToWord(pdfPath);
                        break;
                    case "markdown":
                        resultFile = storageService.convertToMarkdown(pdfPath);
                        break;
                    case "json":
                        resultFile = storageService.convertToJson(pdfPath);
                        break;
                    case "csv":
                        resultFile = storageService.convertToCsv(pdfPath);
                        break;
                    case "jpg":
                        resultFile = storageService.convertToJpg(pdfPath);
                        break;
                    case "merge":
                        resultFile = new File(pdfPath); // return original
                        break;
                    default:
                        redirectAttributes.addFlashAttribute("error", "Invalid format selected: " + format);
                        return "redirect:/";
                }
            }

            // Success → return file download
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resultFile.getName() + "\"")
                    .body(new FileSystemResource(resultFile));

        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Processing failed: " + e.getMessage());
            return "redirect:/";
        } finally {
            // Always try to clean up source files
            cleanupSavedFiles(savedPaths);
        }
    }

    // Helper method to safely delete saved files
    private void cleanupSavedFiles(List<String> paths) {
        for (String path : paths) {
            try {
                Files.deleteIfExists(Paths.get(path));
            } catch (IOException ignored) {
                // log if needed
            }
        }
    }
}