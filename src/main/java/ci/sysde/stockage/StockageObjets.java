package ci.sysde.stockage;

/**
 * Stockage des fichiers (contours importés, bientôt les photos), à part de la base (EXI-08). L'implémentation actuelle écrit sur
 * disque ({@link StockageLocal}) ; un adaptateur MinIO la remplacera sans toucher aux appelants.
 */
public interface StockageObjets {

    /** Enregistre le contenu sous une clé (dossier/nom) et renvoie cette clé. Ne remplace jamais un fichier existant. */
    String enregistrer(String dossier, String nom, byte[] contenu);

    byte[] lire(String cle);
}
