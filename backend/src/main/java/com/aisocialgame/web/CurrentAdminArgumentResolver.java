package com.aisocialgame.web;

import com.aisocialgame.service.AdminAuthService;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentAdminArgumentResolver implements HandlerMethodArgumentResolver {
    private static final String ADMIN_HEADER = "X-Admin-Token";

    private final AdminAuthService adminAuthService;

    public CurrentAdminArgumentResolver(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAdmin.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        return adminAuthService.requireAdmin(webRequest.getHeader(ADMIN_HEADER));
    }
}
