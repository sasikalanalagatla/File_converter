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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

        List<Path> uploadedPaths = new ArrayList<>();

        try {
            if (files == null || files.length == 0) {
                logger.warn("No files uploaded");
                redirectAttributes.addFlashAttribute("error", "Please upload at least one file!");
                return ResponseEntity.badRequest().body(null);
            }

            // Upload and validate files
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;

                String filename = file.getOriginalFilename();
                if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
                    logger.warn("Invalid file: {}", filename);
                    redirectAttributes.addFlashAttribute("error", "Only PDF files are allowed!");
                    cleanupUploadedFiles(uploadedPaths);
                    return ResponseEntity.badRequest().body(null);
                }

                String savedPath = storageService.save(file);
                uploadedPaths.add(Path.of(savedPath));
                logger.info("Saved uploaded PDF temporarily: {}", savedPath);
            }

            if (uploadedPaths.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "No valid PDF files uploaded!");
                return ResponseEntity.badRequest().body(null);
            }

            byte[] resultBytes;
            String filename;

            // Multi-file → merge
            if (uploadedPaths.size() > 1) {
                resultBytes = storageService.mergePdfsBytes(
                        uploadedPaths.stream().map(Path::toString).toList());
                filename = "merged.pdf";
            } else {
                // Single file conversion
                String pdfPath = uploadedPaths.get(0).toString();

                if (format == null || format.trim().isEmpty()) {
                    logger.warn("No format selected");
                    redirectAttributes.addFlashAttribute("error", "Please select a format!");
                    cleanupUploadedFiles(uploadedPaths);
                    return ResponseEntity.badRequest().body(null);
                }

                switch (format.toLowerCase()) {
                    case "jpg":
                        resultBytes = storageService.convertToJpgBytes(pdfPath);
                        filename = "pdf-pages.zip";
                        break;

                    case "word":
                        resultBytes = storageService.convertToWordBytes(pdfPath);
                        filename = "converted.docx";
                        break;

                    case "markdown":
                        resultBytes = storageService.convertToMarkdownBytes(pdfPath);
                        filename = "converted.md";
                        break;

                    case "json":
                        resultBytes = storageService.convertToJsonBytes(pdfPath);
                        filename = "converted.json";
                        break;

                    case "csv":
                        resultBytes = storageService.convertToCsvBytes(pdfPath);
                        filename = "converted.csv";
                        break;

                    case "merge":
                        // For single file "merge" → just return original
                        resultBytes = Files.readAllBytes(Path.of(pdfPath));
                        filename = "original.pdf";
                        break;

                    default:
                        logger.warn("Invalid format: {}", format);
                        redirectAttributes.addFlashAttribute("error", "Invalid format: " + format);
                        cleanupUploadedFiles(uploadedPaths);
                        return ResponseEntity.badRequest().body(null);
                }
            }

            // Clean up only the uploaded PDFs
            cleanupUploadedFiles(uploadedPaths);

            // Return the result as download
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(resultBytes);

        } catch (Exception e) {
            logger.error("Conversion failed", e);
            redirectAttributes.addFlashAttribute("error", "Error during processing: " + e.getMessage());
            cleanupUploadedFiles(uploadedPaths);
            return ResponseEntity.status(500).body(null);
        }
    }

    private void cleanupUploadedFiles(List<Path> paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
                logger.debug("Deleted uploaded temp file: {}", path);
            } catch (IOException e) {
                logger.warn("Failed to delete uploaded temp file: {}", path, e);
            }
        }
    }
}