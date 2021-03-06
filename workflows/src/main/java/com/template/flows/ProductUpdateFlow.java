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



import static net.corda.core.contracts.ContractsDSL.requireThat;

public class ProductUpdateFlow {

    @StartableByRPC
    @InitiatingFlow
    public static class Initiator extends FlowLogic<SignedTransaction> {
//        private final UniqueIdentifier linearId;
        private final Party otherParty;
        private final Party from;
        private final String status;
        private final String color;
        private final String PENDING_STATUS = "Pending";


        private final Step GET_PRODUCT_FROM_VAULT = new Step("Obtaining product from vault.");
        private final Step CHECK_INITIATOR = new Step("Checking current product owner lender is initiating flow.");
        private final Step VERIFYING_TRANSACTION = new Step("Verifying contract constraints.");
        private final Step BUILD_TRANSACTION = new Step("Building and verifying transaction.");
        private final Step SIGN_TRANSACTION = new Step("Signing transaction.");
        private final Step SYNC_OTHER_IDENTITIES = new Step("Making counterparties sync identities with each other.");
        private final Step FINALISE = new Step("Finalising transaction.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };

        private final ProgressTracker progressTracker = new ProgressTracker(GET_PRODUCT_FROM_VAULT, CHECK_INITIATOR, BUILD_TRANSACTION, VERIFYING_TRANSACTION, SIGN_TRANSACTION, FINALISE);

//        public Initiator(String inputExternalId, Party from, Party otherParty, String status) {
        public Initiator(Party from, Party otherParty, String status, String color) {
//            this.linearId = UniqueIdentifier.Companion.fromString(inputExternalId);
            this.otherParty = otherParty;
            this.status = status;
            this.from = from;
            this.color = color;
        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);
            progressTracker.setCurrentStep(GET_PRODUCT_FROM_VAULT);
//            final StateAndRef<ProductState> productFromVault = getProductStateByLinearId(linearId);
            final StateAndRef<ProductState> productFromVault = getUnconsumedProductStateFromVault(color);
            final ProductState productToMarkAsConsumed = productFromVault.getState().getData();
//            final ProductState productToMarkAsConsumed = getUnconsumedProductStateFromVault();
//            if(!"Pending".equals(productToMarkAsConsumed.getStatus())) {
//                throw new FlowException(String.format("Product status in the vault is not Pending. Which suggests Product might have already been processed. Please check"));
//            }
//            final ProductState newInputProduct = new ProductState(from, otherParty, productToMarkAsConsumed.getProductName(), productToMarkAsConsumed.getProductColor(), status, linearId);
            final ProductState newInputProduct = new ProductState(from, otherParty, productToMarkAsConsumed.getProductName(), productToMarkAsConsumed.getProductColor(), status, productToMarkAsConsumed.getLinearId());
            final Party from = (Party) newInputProduct.getFrom();

            final Command<ProductContract.Commands.UpdateStatus> txCommand = new Command<>(
                    new ProductContract.Commands.UpdateStatus(),
                    ImmutableList.of(newInputProduct.getFrom().getOwningKey(), newInputProduct.getTo().getOwningKey()));
            final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                    .addInputState(productFromVault)
                    .addOutputState(newInputProduct, ProductContract.PRODUCT_CONTRACT_ID)
                    .addCommand(txCommand);

            progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
            txBuilder.verify(getServiceHub());

            progressTracker.setCurrentStep(SIGN_TRANSACTION);
            final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder, newInputProduct.getFrom().getOwningKey());
            FlowSession otherPartySession = initiateFlow(otherParty);
            final SignedTransaction fullySignedTx = subFlow(
                    new CollectSignaturesFlow(partSignedTx, ImmutableSet.of(otherPartySession), CollectSignaturesFlow.Companion.tracker()));
            progressTracker.setCurrentStep(FINALISE);
            return subFlow(new FinalityFlow(fullySignedTx, ImmutableSet.of(otherPartySession)));
        }


        private StateAndRef<ProductState> getUnconsumedProductStateFromVault(String color) throws FlowException {
//            QueryCriteria queryCriteria =
//                    new QueryCriteria.LinearStateQueryCriteria(null,null,Vault.StateStatus.UNCONSUMED,null);

//            List<StateAndRef<ProductState>> productStates = getServiceHub().getVaultService().queryBy(ProductState.class, queryCriteria).getStates();

            StateAndRef<ProductState> fromVaultStateAndRef = getServiceHub().getVaultService().
                    queryBy(ProductState.class).getStates().stream()
                    .filter(productState -> productState.getState().getData().getStatus().equals(PENDING_STATUS))
                    .filter(productState -> productState.getState().getData().getProductColor().equals(color)).findAny()
                    .orElseThrow(() -> new IllegalArgumentException("Product State with Pending status && "+color+" does not exist in vault"));


//            boolean productAvailableForUpdate = false;
//                if(null == productStates) {
//                    throw new FlowException(String.format("Product State with UNCONSUMED status does not exist"));
//                }else{
//                for (int i = 0; i < productStates.size(); i++) {
//                    if ("Pending".equals(productStates.get(i).getState().getData().getStatus())) {
//                        productAvailableForUpdate = true;
//                        return productStates.get(i);
//                    }
//                }
//                if (!productAvailableForUpdate) {
//                    throw new FlowException(String.format("Product States with UNCONSUMED && Pending status do not exist"));
//                }
//            }
            // This line is not reached but retained to satisfy return statement
            return fromVaultStateAndRef;
        }

        StateAndRef<ProductState> getProductStateByLinearId(UniqueIdentifier linearId) throws FlowException {
            QueryCriteria queryCriteria = new QueryCriteria.LinearStateQueryCriteria(
                    null,
                    ImmutableList.of(linearId),
                    Vault.StateStatus.UNCONSUMED,
                    null);

            List<StateAndRef<ProductState>> productStates = getServiceHub().getVaultService().queryBy(ProductState.class, queryCriteria).getStates();
            if (productStates.size() != 1) {
//                    Removing this code to get all states by state of UNCONSUMED
                                throw new FlowException(String.format("Product with id %s not found.", linearId));
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
