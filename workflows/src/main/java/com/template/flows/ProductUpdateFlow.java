package com.template.flows;

import net.corda.core.contracts.UniqueIdentifier;
import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.template.contracts.ProductContract;
import com.template.states.ProductState;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.crypto.SecureHash;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import java.util.List;
import java.util.UUID;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class ProductUpdateFlow {

    @StartableByRPC
    @InitiatingFlow
    public static class Initiator extends FlowLogic<SignedTransaction> {
        private final UniqueIdentifier linearId;
        private final Party otherParty;
        private final Party from;
        private final String status;


        private final Step GET_PRODUCT_FROM_VAULT = new Step("Obtaining product from vault.");
        private final Step CHECK_INITIATOR = new Step("Checking current product owner lender is initiating flow.");
        private final Step BUILD_TRANSACTION = new Step("Building and verifying transaction.");
        private final Step SIGN_TRANSACTION = new Step("Signing transaction.");
        private final Step SYNC_OTHER_IDENTITIES = new Step("Making counterparties sync identities with each other.");
        private final Step FINALISE = new Step("Finalising transaction.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };

        private final ProgressTracker progressTracker = new ProgressTracker(GET_PRODUCT_FROM_VAULT, CHECK_INITIATOR, BUILD_TRANSACTION, SIGN_TRANSACTION, FINALISE);

        public Initiator(String inputExternalId, Party from, Party otherParty, String status) {
            this.linearId = UniqueIdentifier.Companion.fromString(inputExternalId);
            System.out.println("##############################################");
            System.out.println("##############################################");
            System.out.println("##############################################");
            System.out.println("##############################################");
            System.out.println("linearId: "+linearId);
            System.out.println("##############################################");
            System.out.println("##############################################");
            System.out.println("##############################################");
            System.out.println("##############################################");
            this.otherParty = otherParty;
            this.status = status;
            this.from = from;
        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);
            // Stage 1. Retrieve obligation with the correct linear ID from the vault.
            progressTracker.setCurrentStep(GET_PRODUCT_FROM_VAULT);
            final StateAndRef<ProductState> productFromVault = getProductStateByLinearId(linearId);
            final ProductState productToMarkAsConsumed = productFromVault.getState().getData();
            final ProductState newInputProduct = new ProductState(from, otherParty, productToMarkAsConsumed.getProductName(), productToMarkAsConsumed.getProductColor(), status, linearId);

            final Party from = (Party) newInputProduct.getFrom();

            final Command<ProductContract.Commands.UpdateStatus> txCommand = new Command<>(
                    new ProductContract.Commands.UpdateStatus(),
                    ImmutableList.of(newInputProduct.getFrom().getOwningKey(), newInputProduct.getTo().getOwningKey()));
            final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                    .addInputState(productFromVault)
                    .addOutputState(newInputProduct, ProductContract.PRODUCT_CONTRACT_ID)
                    .addCommand(txCommand);

            // Stage 6. Sign the transaction using the key we originally used.
            progressTracker.setCurrentStep(SIGN_TRANSACTION);
            final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder, newInputProduct.getFrom().getOwningKey());
            // Stage 4.
//            progressTracker.setCurrentStep(COLLECT_SIGNS);
            // Send the state to the counterparty, and receive it back with their signature.
            FlowSession otherPartySession = initiateFlow(otherParty);
            final SignedTransaction fullySignedTx = subFlow(
                    new CollectSignaturesFlow(partSignedTx, ImmutableSet.of(otherPartySession), CollectSignaturesFlow.Companion.tracker()));
           // Stage 10. Notarise and record the transaction in our vaults.
            progressTracker.setCurrentStep(FINALISE);
            return subFlow(new FinalityFlow(fullySignedTx, ImmutableSet.of(otherPartySession)));
        }

        StateAndRef<ProductState> getProductStateByLinearId(UniqueIdentifier linearId) throws FlowException {
            QueryCriteria queryCriteria = new QueryCriteria.LinearStateQueryCriteria(
                    null,
                    ImmutableList.of(linearId),
                    Vault.StateStatus.UNCONSUMED,
                    null);
            List<StateAndRef<ProductState>> productStates = getServiceHub().getVaultService().queryBy(ProductState.class, queryCriteria).getStates();
            if (productStates.size() != 1) {
                System.out.println("##############################################");
                System.out.println("##############################################");
                System.out.println("##############################################");
                System.out.println("##############################################");
                System.out.println("##############################################");
                System.out.println("##############################################");
                System.out.println("##############################################");
                System.out.println("##############################################");
                throw new FlowException(String.format("@@@@@@@ - Product with id %s not found.", linearId));
            }
            return productStates.get(0);
        }
    }

    @InitiatedBy(Initiator.class)
    public static class Acceptor extends FlowLogic<SignedTransaction> {

        private final FlowSession otherPartySession;

        public Acceptor(FlowSession otherPartySession) {
            this.otherPartySession = otherPartySession;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            class SignTxFlow extends SignTransactionFlow {
                private SignTxFlow(FlowSession otherPartyFlow, ProgressTracker progressTracker) {
                    super(otherPartyFlow, progressTracker);
                }

                @Override
                protected void checkTransaction(SignedTransaction stx) {
                    requireThat(require -> {
                        ContractState output = stx.getTx().getOutputs().get(0).getData();
                        require.using("This must be a product transaction.", output instanceof ProductState);
                        ProductState productState = (ProductState) output;
                        require.using("Status of a created product should be Received.",("Received".equals(productState.getStatus())));
                        return null;
                    });
                }
            }
            final SignTxFlow signTxFlow = new SignTxFlow(otherPartySession, SignTransactionFlow.Companion.tracker());
            final SecureHash txId = subFlow(signTxFlow).getId();

            return subFlow(new ReceiveFinalityFlow(otherPartySession, txId));
        }
    }

}
