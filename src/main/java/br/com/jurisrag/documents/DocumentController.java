package br.com.jurisrag.documents;

import br.com.jurisrag.security.TenantResolver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@Validated
public class DocumentController {
    private final DocumentService service;
    private final TenantResolver tenantResolver;

    public DocumentController(DocumentService service, TenantResolver tenantResolver) {
        this.service = service;
        this.tenantResolver = tenantResolver;
    }

    @PostMapping(path = "/v1/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            JwtAuthenticationToken authentication,
            @RequestPart("file") MultipartFile file,
            @RequestPart("title") @NotBlank @Size(max = 200) String title) {
        UploadResponse response = service.upload(tenantResolver.requireTenant(authentication), title, file);
        URI statusUri = ServletUriComponentsBuilder.fromCurrentContextPath().path(response.statusUrl()).build().toUri();
        return ResponseEntity.accepted().location(statusUri).body(response);
    }

    @GetMapping("/v1/documents/{id}")
    public DocumentStatusResponse status(JwtAuthenticationToken authentication, @PathVariable UUID id) {
        return service.status(tenantResolver.requireTenant(authentication), id);
    }
}
