package com.aisocialgame.web;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.User;
import com.aisocialgame.service.AuthService;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
    private static final String AUTH_HEADER = "X-Auth-Token";

    private final AuthService authService;

    public CurrentUserArgumentResolver(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && User.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        CurrentUser annotation = parameter.getParameterAnnotation(CurrentUser.class);
        boolean required = annotation == null || annotation.required();
        User user = authService.authenticate(webRequest.getHeader(AUTH_HEADER));
        if (user == null && required) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return user;
    }
}
