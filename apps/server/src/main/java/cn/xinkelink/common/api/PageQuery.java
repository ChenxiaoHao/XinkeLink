package cn.xinkelink.common.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

public record PageQuery(@Min(1) Integer page, Integer pageSize) {
    public PageQuery {
        page = page == null ? 1 : page;
        pageSize = pageSize == null ? 10 : pageSize;
    }

    @AssertTrue(message = "每页条数仅支持10、20或50")
    public boolean isPageSizeValid() { return pageSize == 10 || pageSize == 20 || pageSize == 50; }

    public long offset() { return ((long) page - 1) * pageSize; }
}
