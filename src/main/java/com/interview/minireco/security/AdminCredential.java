package com.interview.minireco.security;

import java.util.Set;

record AdminCredential(String keyId, String secret, String role, Set<AdminPermission> permissions) {
    AdminCredential {
        permissions = Set.copyOf(permissions);
    }
}
