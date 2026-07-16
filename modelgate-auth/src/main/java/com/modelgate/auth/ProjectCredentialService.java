package com.modelgate.auth;

import com.modelgate.common.api.AdminDtos.CreateApiKeyResponse;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.ProjectRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@Service
public class ProjectCredentialService {
    private final ProjectRepository projects;
    private final VirtualKeyService keys;
    public ProjectCredentialService(ProjectRepository projects, VirtualKeyService keys) { this.projects = projects; this.keys = keys; }
    public CreateApiKeyResponse generate(long serviceAccountId, boolean rotate) {
        var existing = projects.activeApplicationKey(serviceAccountId);
        if (existing.isPresent()) { if (!rotate) throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST,"An active application credential already exists. Rotate it instead."); keys.disable(existing.get()); }
        String value="mg-key-"+Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8));
        long id=projects.insertApplicationKey(serviceAccountId, value.substring(0,Math.min(18,value.length())),VirtualKeyService.sha256(value));
        return new CreateApiKeyResponse(id,value.substring(0,Math.min(18,value.length())),value,true);
    }
}
