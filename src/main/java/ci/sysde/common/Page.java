package ci.sysde.common;

import java.util.List;
import java.util.function.Function;

/** Une page de résultats. Les noms de champs suivent ceux du front (content, totalElements…). */
public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> Page<T> de(List<T> content, PageRequete demande, long total) {
        int pages = (int) Math.ceil(total / (double) demande.size());
        return new Page<>(List.copyOf(content), demande.page(), demande.size(), total, pages);
    }

    public <R> Page<R> mapper(Function<? super T, ? extends R> f) {
        return new Page<>(content.stream().<R>map(f).toList(), page, size, totalElements, totalPages);
    }
}
