package com.iLovePdf.conversion.controller;

import com.iLovePdf.conversion.service.FilesStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(PdfController.class);

    @Autowired
    FilesStorageService storageService;

    @GetMapping("/")
    public String uploadForm() {
        logger.info("Rendering upload form");
        return "upload";
    }

    @PostMapping("/convert")
    public Object convert(
            @RequestParam("file") MultipartFile[] files,
            @RequestParam(value = "format", required = false) String format,
            RedirectAttributes redirectAttributes) {

        logger.info("Received convert request");

        if (files == null || files.length == 0) {
            logger.warn("No files uploaded");
            redirectAttributes.addFlashAttribute("error", "Please upload at least one file!");
            return "redirect:/";
        }

        List<String> savedPaths = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                logger.warn("Encountered empty file in upload list");
                continue;
            }

            String filename = file.getOriginalFilename();
            if (filename == null || filename.trim().isEmpty()) {
                logger.warn("Uploaded file has no name");
                continue;
            }

            boolean isPdf = filename.toLowerCase().endsWith(".pdf") &&
                    ("application/pdf".equals(file.getContentType()) ||
                            file.getContentType() == null);

            if (!isPdf) {
                logger.warn("Invalid file type: {}", filename);
                redirectAttributes.addFlashAttribute("error", "Only PDF files are allowed! Invalid file: " + filename);
                cleanupSavedFiles(savedPaths);
                return "redirect:/";
            }

            try {
                String savedPath = storageService.save(file);
                savedPaths.add(savedPath);
                logger.info("Saved file: {}", savedPath);
            } catch (Exception e) {
                logger.error("Failed to save file: {}", filename, e);
                redirectAttributes.addFlashAttribute("error", "Failed to save file: " + filename);
                cleanupSavedFiles(savedPaths);
                return "redirect:/";
            }
        }

        if (savedPaths.isEmpty()) {
            logger.warn("No valid PDF files after validation");
            redirectAttributes.addFlashAttribute("error", "No valid PDF files were uploaded!");
            return "redirect:/";
        }

        File resultFile;

        try {
            if (savedPaths.size() > 1) {
                logger.info("Merging {} PDF files", savedPaths.size());
                resultFile = storageService.mergePdfs(savedPaths);
            } else {
                String pdfPath = savedPaths.get(0);

                if (format == null || format.trim().isEmpty()) {
                    logger.warn("No format selected for single PDF");
                    redirectAttributes.addFlashAttribute("error", "Please select a format for single PDF!");
                    return "redirect:/";
                }

                logger.info("Converting PDF {} to format {}", pdfPath, format);

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
                        resultFile = new File(pdfPath);
                        break;
                    default:
                        logger.warn("Invalid format selected: {}", format);
                        redirectAttributes.addFlashAttribute("error", "Invalid format selected: " + format);
                        return "redirect:/";
                }
            }

            logger.info("Processing completed successfully. Returning file: {}", resultFile.getName());

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + resultFile.getName() + "\"")
                    .body(new FileSystemResource(resultFile));

        } catch (Exception e) {
            logger.error("Processing failed", e);
            redirectAttributes.addFlashAttribute("error", "Processing failed: " + e.getMessage());
            return "redirect:/";
        } finally {
            cleanupSavedFiles(savedPaths);
            logger.info("Cleaned up temporary uploaded files");
        }
    }

    private void cleanupSavedFiles(List<String> paths) {
        for (String path : paths) {
            try {
                Files.deleteIfExists(Paths.get(path));
                logger.info("Deleted temp file: {}", path);
            } catch (IOException e) {
                logger.warn("Failed to delete temp file: {}", path, e);
            }
        }
    }
}