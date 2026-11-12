package store.order;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record OrderIn(@NotEmpty @Size(max=100) List<@NotNull @Valid Item> items) {
    public record Item(@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String idProduct, @Min(1) @Max(10000) int quantity) {}
}
