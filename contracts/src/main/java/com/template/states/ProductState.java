package com.template.states;

import com.template.contracts.ProductContract;
import net.corda.core.contracts.BelongsToContract;
import net.corda.core.contracts.LinearState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.AbstractParty;
import net.corda.core.serialization.ConstructorForDeserialization;
import com.google.common.collect.ImmutableList;

import java.util.List;

@BelongsToContract(ProductContract.class)
public class ProductState implements LinearState {
    private final AbstractParty to;
    private final AbstractParty from;
    private final String productName;
    private final String productColor;
    private final String status;
    private final UniqueIdentifier linearId;

    public AbstractParty getTo() {
        return to;
    }

    public AbstractParty getFrom() {
        return from;
    }

    public String getProductName() {
        return productName;
    }

    public String getProductColor() {
        return productColor;
    } // Only Red or Green allowed. This is addressed in contract

    public String getStatus() {
        return status;
    }

    public UniqueIdentifier getLinearId() {
        return linearId;
    }

    @ConstructorForDeserialization
    public ProductState(AbstractParty from, AbstractParty to, String productName, String productColor, String status, UniqueIdentifier linearId) {
        this.from = from;
        this.to = to;
        this.productName = productName;
        this.productColor = productColor;
        this.status = status;
        this.linearId = linearId;
    }


    @Override
    public List<AbstractParty> getParticipants() {
        return ImmutableList.of(from, to);
    }

}