package ci.sysde.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.iam.auth.AuthDtos.LoginResponse;
import ci.sysde.iam.compte.Compte;
import ci.sysde.iam.compte.CompteRepository;
import ci.sysde.security.ConnexionProperties;
import ci.sysde.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");
    private static final String IP = "10.0.0.7";

    @Mock CompteRepository comptes;
    @Mock ComptesQuery requetes;
    @Mock JournalConnexion journal;
    @Mock JwtService jwt;

    private final PasswordEncoder encodeur = new BCryptPasswordEncoder(4); // coût réduit : tests rapides
    private AuthService service;
    private Compte compte;

    @BeforeEach
    void preparer() {
        service = new AuthService(comptes, requetes, journal, encodeur, jwt,
                new ConnexionProperties(3, Duration.ofMinutes(15)),
                Clock.fixed(T0, ZoneOffset.UTC));
        compte = CompteTest.newCompte();
        ReflectionTestUtils.setField(compte, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(compte, "operateurId", UUID.randomUUID());
        ReflectionTestUtils.setField(compte, "matricule", "SAPH-00348");
        ReflectionTestUtils.setField(compte, "profil", "MO");
        ReflectionTestUtils.setField(compte, "statut", "ACTIF");
        ReflectionTestUtils.setField(compte, "motDePasseHash", encodeur.encode("Hevea2026ok"));
        ReflectionTestUtils.setField(compte, "doitChangerMotDePasse", false);
    }

    @Test
    void connexionReussie() {
        when(comptes.findByMatricule("SAPH-00348")).thenReturn(Optional.of(compte));
        when(requetes.peutSeConnecter(compte.id())).thenReturn(true);
        when(requetes.permissionsDuProfil("MO")).thenReturn(List.of("REALISER_ACTIVITE"));
        when(jwt.emettre(eq(compte.id()), eq("SAPH-00348"), eq("MO"), eq(compte.operateurId()), any()))
                .thenReturn(new JwtService.Jeton("jeton", T0.plusSeconds(3600)));

        LoginResponse r = service.connecter("  saph-00348 ", "Hevea2026ok", IP);

        assertThat(r.token()).isEqualTo("jeton");
        assertThat(r.mustChangePassword()).isFalse();
        verify(journal).tracer(compte.id(), "SAPH-00348", "SUCCES", IP);
    }

    @Test
    void matriculeInconnuRenvoieLeMemeMessageQuUnMauvaisMotDePasse() {
        when(comptes.findByMatricule("XXXX-00001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connecter("XXXX-00001", "peuImporte1A", IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.code()).isEqualTo("IDENTIFIANTS_INVALIDES");
                    assertThat(e.getMessage()).isEqualTo(AuthService.MESSAGE_IDENTIFIANTS);
                });
        verify(journal).tracer(null, "XXXX-00001", "ECHEC", IP);
    }

    @Test
    void mauvaisMotDePasseCompteLesEchecsPuisVerrouille() {
        when(comptes.findByMatricule("SAPH-00348")).thenReturn(Optional.of(compte));

        for (int i = 1; i <= 2; i++) {
            assertThatThrownBy(() -> service.connecter("SAPH-00348", "mauvais", IP))
                    .isInstanceOf(BusinessException.class);
        }
        verify(journal, org.mockito.Mockito.times(2)).tracer(compte.id(), "SAPH-00348", "ECHEC", IP);
        assertThat(compte.echecsConnexion()).isEqualTo((short) 2);

        // 3e échec : verrouillage (seuil de test = 3)
        assertThatThrownBy(() -> service.connecter("SAPH-00348", "mauvais", IP))
                .isInstanceOf(BusinessException.class);
        verify(journal).tracer(compte.id(), "SAPH-00348", "VERROUILLE", IP);
        assertThat(compte.estVerrouille(T0)).isTrue();

        // Même avec le bon mot de passe, le compte reste verrouillé.
        assertThatThrownBy(() -> service.connecter("SAPH-00348", "Hevea2026ok", IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.LOCKED);
                    assertThat(e.code()).isEqualTo("COMPTE_VERROUILLE");
                });
    }

    @Test
    void compteSansMotDePasseEstRefuseSansRevelerSonExistence() {
        ReflectionTestUtils.setField(compte, "motDePasseHash", null);
        when(comptes.findByMatricule("SAPH-00348")).thenReturn(Optional.of(compte));

        assertThatThrownBy(() -> service.connecter("SAPH-00348", "Hevea2026ok", IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("IDENTIFIANTS_INVALIDES"));
    }

    @Test
    void compteInactifEstRefuseApresVerificationDuMotDePasse() {
        when(comptes.findByMatricule("SAPH-00348")).thenReturn(Optional.of(compte));
        when(requetes.peutSeConnecter(compte.id())).thenReturn(false);

        assertThatThrownBy(() -> service.connecter("SAPH-00348", "Hevea2026ok", IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.code()).isEqualTo("COMPTE_INACTIF");
                });
        verify(jwt, never()).emettre(any(), any(), any(), any(), any());
    }

    @Test
    void changementDeMotDePasseExigeLActuelEtUneRegleDeRobustesse() {
        when(comptes.findById(compte.id())).thenReturn(Optional.of(compte));

        assertThatThrownBy(() -> service.changerMotDePasse(compte.id(), "faux", "NouveauMdp2026", IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("MOT_DE_PASSE_ACTUEL_INCORRECT"));
        assertThatThrownBy(() -> service.changerMotDePasse(compte.id(), "Hevea2026ok", "faible", IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("MOT_DE_PASSE_FAIBLE"));
        assertThatThrownBy(() -> service.changerMotDePasse(compte.id(), "Hevea2026ok", "Hevea2026ok", IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("MOT_DE_PASSE_IDENTIQUE"));
    }

    @Test
    void changementDeMotDePasseReussiEstTrace() {
        when(comptes.findById(compte.id())).thenReturn(Optional.of(compte));
        ReflectionTestUtils.setField(compte, "doitChangerMotDePasse", true);

        service.changerMotDePasse(compte.id(), "Hevea2026ok", "NouveauMdp2026", IP);

        assertThat(encodeur.matches("NouveauMdp2026", compte.motDePasseHash())).isTrue();
        assertThat(compte.doitChangerMotDePasse()).isFalse();
        verify(journal).tracer(compte.id(), "SAPH-00348", "MOT_DE_PASSE_CHANGE", IP);
    }
}
