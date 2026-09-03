package eu.rekawek.coffeegb.ui.menu;

/** Zero-based pagination metadata for an immutable menu page. */
public record MenuPagination(int pageIndex, int pageCount) {

    private static final MenuPagination SINGLE_PAGE = new MenuPagination(0, 1);

    public MenuPagination {
        if (pageCount < 1) {
            throw new IllegalArgumentException("pageCount must be positive");
        }
        if (pageIndex < 0 || pageIndex >= pageCount) {
            throw new IllegalArgumentException("pageIndex must be within pageCount");
        }
    }

    public static MenuPagination singlePage() {
        return SINGLE_PAGE;
    }

    public boolean hasPreviousPage() {
        return pageIndex > 0;
    }

    public boolean hasNextPage() {
        return pageIndex + 1 < pageCount;
    }
}
