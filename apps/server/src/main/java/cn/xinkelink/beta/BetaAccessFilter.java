package cn.xinkelink.beta;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;

/** Runs after Bearer authentication and before business authorization, including anonymous reads. */
public final class BetaAccessFilter extends OncePerRequestFilter {
    private final BetaService beta;
    private final JsonMapper json;
    public BetaAccessFilter(BetaService beta,JsonMapper json){this.beta=beta;this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(path.startsWith("/api/v1/")&&!allowed(path,request.getMethod())){
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            AuthPrincipal actor=authentication!=null&&authentication.getPrincipal() instanceof AuthPrincipal principal?principal:null;
            try{beta.requireAccess(actor);}catch(BusinessException denied){write(response,denied.code());return;}
            catch(RuntimeException unavailable){write(response,ErrorCode.SERVICE_UNAVAILABLE);return;}
        }
        chain.doFilter(request,response);
    }
    private static boolean allowed(String path,String method){
        return path.startsWith("/api/v1/admin/")||path.startsWith("/api/v1/auth/")||path.equals("/api/v1/health")
                ||path.equals("/api/v1/beta/access")&&method.equals("GET")||path.equals("/api/v1/beta/redeem")&&method.equals("POST")
                ||path.equals("/api/v1/me")&&(method.equals("GET")||method.equals("PATCH"))
                // Multipart purpose is parsed by MVC; MediaService enforces avatar-only before storing bytes.
                ||path.equals("/api/v1/images")&&method.equals("POST")
                // Native image requests use signed viewer grants rather than a Bearer header.
                ||path.matches("/api/v1/images/[0-9]+/content")&&method.equals("GET");
    }
    private void write(HttpServletResponse response,ErrorCode code)throws IOException{response.setStatus(code.status().value());response.setContentType("application/json");response.setCharacterEncoding("UTF-8");json.writeValue(response.getWriter(),ApiResponse.failure(code));}
}
