package ci.sysde.common;

/**
 * Pagination demandée par un client : {@code ?page=0&size=25}. Les bornes sont imposées côté serveur
 * (jamais plus de {@value #TAILLE_MAX} lignes par page) pour protéger la base.
 */
public record PageRequete(int page, int size) {

    public static final int TAILLE_DEFAUT = 25;
    public static final int TAILLE_MAX = 100;

    public PageRequete {
        if (page < 0) {
            page = 0;
        }
        if (size < 1) {
            size = TAILLE_DEFAUT;
        }
        if (size > TAILLE_MAX) {
            size = TAILLE_MAX;
        }
    }

    /** Valeurs absentes ou invalides remplacées par les défauts. */
    public static PageRequete de(Integer page, Integer size) {
        return new PageRequete(page == null ? 0 : page, size == null ? TAILLE_DEFAUT : size);
    }

    /** Nombre de lignes à sauter ({@code OFFSET}). */
    public long decalage() {
        return (long) page * size;
    }
}
