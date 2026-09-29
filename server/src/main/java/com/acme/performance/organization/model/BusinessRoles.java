package com.acme.performance.organization.model;

import java.util.Set;

public final class BusinessRoles {
    public static final String TECHNICIAN = "TECHNICIAN";
    public static final String ASSISTANT_ENGINEER = "ASSISTANT_ENGINEER";
    public static final String SUPERVISOR = "SUPERVISOR";
    public static final String DEPARTMENT_MANAGER = "DEPARTMENT_MANAGER";

    public static final Set<String> ALL = Set.of(
            TECHNICIAN, ASSISTANT_ENGINEER, SUPERVISOR, DEPARTMENT_MANAGER);
    public static final Set<String> SCORED = Set.of(TECHNICIAN, ASSISTANT_ENGINEER);
    public static final Set<String> MANAGEMENT = Set.of(SUPERVISOR, DEPARTMENT_MANAGER);

    private BusinessRoles() {}
}
