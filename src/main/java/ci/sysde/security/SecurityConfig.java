package ci.sysde.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * API sans état : jeton Bearer (JWT signé HS256) qui prouve l'identité ; profil et permissions sont relus en base
 * ({@link JwtEtatConverter}) et deviennent des autorités {@code PERM_xxx}. Seules la connexion et la documentation sont publiques.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
                                    JwtEtatConverter convertisseur) throws Exception {
        AuthenticationEntryPoint nonAuthentifie = (req, res, ex) -> ecrire(res, HttpStatus.UNAUTHORIZED,
                "SESSION_EXPIREE", "Votre session a expiré : reconnectez-vous.");
        http
                .csrf(csrf -> csrf.disable())            // API à jeton Bearer, sans cookie de session
                .cors(c -> c.configurationSource(cors))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/auth/login", "/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Compte désactivé depuis peu (envoi seul) : uniquement l'envoi de ce qui a été saisi hors ligne.
                        .requestMatchers(HttpMethod.PUT, "/realisations/*", "/participants/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/participants", "/mobile/synchronisation").authenticated()
                        .anyRequest().access((auth, contexte) -> new AuthorizationDecision(
                                auth.get() instanceof JwtAuthenticationToken jeton && jeton.isAuthenticated()
                                        && !aAutorite(jeton, JwtEtatConverter.ENVOI_SEUL)
                                        && (!aAutorite(jeton, JwtEtatConverter.MOT_DE_PASSE_TEMPORAIRE)
                                                || routeDuMotDePasseTemporaire(contexte.getRequest())))))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(nonAuthentifie)
                        .jwt(j -> j.jwtAuthenticationConverter(convertisseur)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(nonAuthentifie)
                        .accessDeniedHandler((req, res, ex) -> {
                            var courant = org.springframework.security.core.context.SecurityContextHolder.getContext()
                                    .getAuthentication();
                            if (courant != null && courant.getAuthorities().stream().anyMatch(
                                    a -> JwtEtatConverter.MOT_DE_PASSE_TEMPORAIRE.equals(a.getAuthority()))) {
                                ecrire(res, HttpStatus.FORBIDDEN, "MOT_DE_PASSE_TEMPORAIRE",
                                        "Votre mot de passe est temporaire : choisissez un mot de passe personnel avant de continuer.");
                            } else {
                                ecrire(res, HttpStatus.FORBIDDEN, "ACCES_REFUSE",
                                        "Vous n'avez pas le droit d'effectuer cette action.");
                            }
                        }));
        return http.build();
    }

    private static boolean aAutorite(JwtAuthenticationToken jeton, String autorite) {
        return jeton.getAuthorities().stream().anyMatch(a -> autorite.equals(a.getAuthority()));
    }

    /** Ce que peut faire un compte au mot de passe temporaire : se voir, changer son mot de passe, se déconnecter. */
    private static boolean routeDuMotDePasseTemporaire(jakarta.servlet.http.HttpServletRequest requete) {
        String chemin = requete.getRequestURI().substring(requete.getContextPath().length());
        return switch (requete.getMethod()) {
            case "GET" -> chemin.equals("/auth/moi");
            case "POST" -> chemin.equals("/auth/mot-de-passe") || chemin.equals("/auth/logout");
            default -> false;
        };
    }

    /** Erreur de sécurité au même format que le reste de l'API (RFC 9457, champs historiques conservés). */
    private static void ecrire(jakarta.servlet.http.HttpServletResponse res, HttpStatus statut, String code, String message)
            throws java.io.IOException {
        res.setStatus(statut.value());
        res.setContentType("application/problem+json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + statut.getReasonPhrase()
                + "\",\"status\":" + statut.value()
                + ",\"detail\":\"" + message + "\",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    @Bean
    SecretKey cleJwt(JwtProperties props) {
        return new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey cle) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(cle));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey cle, JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(cle).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> validateur = JwtValidators.createDefaultWithIssuer(props.issuer());
        decoder.setJwtValidator(validateur);
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${sysde.cors.origines}") List<String> origines) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origines);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match"));
        c.setExposedHeaders(List.of("ETag"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }
}
