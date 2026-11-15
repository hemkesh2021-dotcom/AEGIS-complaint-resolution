package com.aegis.api.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aegis.api.ApiKeyFilter;
import com.aegis.api.service.ProviderChatModel;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ProviderControllerTest {
    @Test void operatorCannotSpendProviderTokensOnProbe() {
        var model=mock(ProviderChatModel.class);
        var request=new MockHttpServletRequest();
        request.setAttribute(ApiKeyFilter.ATTR_ROLES,Set.of("operator"));
        assertEquals(403,new ProviderController(model).test(request).getStatusCode().value());
        verifyNoInteractions(model);
    }
    @Test void supervisorNeedsConfiguredProvider() {
        var model=mock(ProviderChatModel.class);
        var request=new MockHttpServletRequest();
        request.setAttribute(ApiKeyFilter.ATTR_ROLES,Set.of("supervisor"));
        assertEquals(409,new ProviderController(model).test(request).getStatusCode().value());
        verify(model,never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }
    @Test void remoteFailureDoesNotExposeErrorDetails() {
        var model=mock(ProviderChatModel.class);
        when(model.configured()).thenReturn(true);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenThrow(new IllegalStateException("secret"));
        var request=new MockHttpServletRequest();request.setAttribute(ApiKeyFilter.ATTR_ROLES,Set.of("supervisor"));
        var response=new ProviderController(model).test(request);
        assertEquals(502,response.getStatusCode().value());assertFalse(response.getBody().toString().contains("secret"));
    }
}
