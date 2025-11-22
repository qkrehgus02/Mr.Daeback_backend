package com.saeal.MrDaebackService.product.domain;

import com.saeal.MrDaebackService.dinner.Dinner;
import com.saeal.MrDaebackService.menuItems.domain.MenuItems;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY,  optional = false)
    private Dinner dinner;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MenuItems> menuItems = new ArrayList<>();

    @Column(nullable = false, length = 100)
    private String productName;

    @Column(nullable = false)
    private BigDecimal totalPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(length = 255)
    private String memo;
}
