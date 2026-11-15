package ci.sysde.stockage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stockage sur disque, sous {@code sysde.stockage.repertoire}. Les clés ne peuvent pas sortir de ce répertoire. */
@Component
class StockageLocal implements StockageObjets {

    private final Path racine;

    StockageLocal(@Value("${sysde.stockage.repertoire:./data/fichiers}") String repertoire) {
        this.racine = Path.of(repertoire).toAbsolutePath().normalize();
    }

    @Override
    public String enregistrer(String dossier, String nom, byte[] contenu) {
        String cle = java.util.Arrays.stream(dossier.replace('\\', '/').split("/")).map(StockageLocal::nettoyer)
                .collect(java.util.stream.Collectors.joining("/")) + "/" + nettoyer(nom);
        Path chemin = resoudre(cle);
        try {
            Files.createDirectories(chemin.getParent());
            Files.write(chemin, contenu, StandardOpenOption.CREATE_NEW);
            return cle;
        } catch (IOException e) {
            throw new UncheckedIOException("Écriture impossible dans le stockage : " + cle, e);
        }
    }

    @Override
    public byte[] lire(String cle) {
        try {
            return Files.readAllBytes(resoudre(cle));
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture impossible dans le stockage : " + cle, e);
        }
    }

    private Path resoudre(String cle) {
        Path chemin = racine.resolve(cle).normalize();
        if (!chemin.startsWith(racine)) {
            throw new IllegalArgumentException("Clé de stockage invalide.");
        }
        return chemin;
    }

    /** Un seul segment, sans séparateur ni point de remontée. */
    private static String nettoyer(String segment) {
        String s = segment.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isBlank() || s.equals(".") || s.equals("..") ? "fichier" : s;
    }
}
