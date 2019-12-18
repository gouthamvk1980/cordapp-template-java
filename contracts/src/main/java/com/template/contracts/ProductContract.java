package com.template.contracts;

import com.template.states.ProductState;
import net.corda.core.contracts.*;
import net.corda.core.identity.AbstractParty;
import net.corda.core.transactions.LedgerTransaction;

import java.security.PublicKey;
import java.util.HashSet;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static net.corda.core.contracts.ContractsDSL.requireSingleCommand;
import static net.corda.core.contracts.ContractsDSL.requireThat;

public class ProductContract implements Contract {
    public static final String PRODUCT_CONTRACT_ID = "com.template.contracts.ProductContract";

    public interface Commands extends CommandData {
        class Create extends TypeOnlyCommandData implements Commands {
        }

        class UpdateStatus extends TypeOnlyCommandData implements Commands {
        }

    }

    @Override
    public void verify(LedgerTransaction tx) {
        final CommandWithParties<Commands> command = requireSingleCommand(tx.getCommands(), Commands.class);
        final Commands commandData = command.getValue();
        final Set<PublicKey> setOfSigners = new HashSet<>(command.getSigners());
        if (commandData instanceof Commands.Create) {
            verifyCreate(tx, setOfSigners);
        } else if (commandData instanceof Commands.UpdateStatus) {
            verifyUpdateStatus(tx, setOfSigners);
        } else {
            throw new IllegalArgumentException("Unrecognised command.");
        }
    }

    private Set<PublicKey> keysFromParticipants(ProductState product) {
        return product
                .getParticipants().stream()
                .map(AbstractParty::getOwningKey)
                .collect(toSet());
    }

    // This only allows one obligation issuance per transaction.
    private void verifyCreate(LedgerTransaction tx, Set<PublicKey> signers) {
        requireThat(req -> {
            req.using("No inputs should be consumed when creating a product.",
                    tx.getInputStates().isEmpty());
            req.using("Only one product state should be created.", tx.getOutputStates().size() == 1);
            ProductState product = (ProductState) tx.getOutputStates().get(0);
            req.using("Name of the product created must be Gadgets", "Gadgets".equals(product.getProductName()));
            req.using("A newly issued Product must have be of color either Red or Green", ("Green".equals(product.getProductColor()) || "Red".equals(product.getProductColor())));
            req.using("A newly issued Product must have status of Pending by default", ("Pending".equals(product.getStatus())));
            req.using("Both sender and receiver company should sign product create transaction.",
                    signers.equals(keysFromParticipants(product)));
            return null;
        });
    }

    // This only allows one obligation transfer per transaction.
    private void verifyUpdateStatus(LedgerTransaction tx, Set<PublicKey> signers) {
        requireThat(req -> {
            req.using("A product update transaction should at least consume one input state.", tx.getInputs().size() == 0);
            req.using("A product update transaction should only consume one input state.", tx.getInputs().size() == 1);
            req.using("A product update transaction should only create one output state.", tx.getOutputs().size() == 1);
            ProductState inputProductState = tx.inputsOfType(ProductState.class).get(0);
            ProductState outputProductState = tx.outputsOfType(ProductState.class).get(0);
            req.using("The Product state must change in an update transaction.", !inputProductState.getStatus().equals(outputProductState.getStatus()));
            req.using("The Product state must change to  Received after update transaction.", "Received".equals(outputProductState.getStatus()));
            req.using("Name of the product after update must remain Gadgets", "Gadgets".equals(outputProductState.getProductColor()));
            req.using("Both sender and receiver company should sign product update status transaction.",
                    signers.equals(keysFromParticipants(outputProductState)));
            return null;
        });
    }

}