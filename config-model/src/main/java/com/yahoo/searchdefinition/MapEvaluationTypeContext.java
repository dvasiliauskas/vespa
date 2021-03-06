// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition;

import com.yahoo.searchlib.rankingexpression.ExpressionFunction;
import com.yahoo.searchlib.rankingexpression.RankingExpression;
import com.yahoo.searchlib.rankingexpression.Reference;
import com.yahoo.searchlib.rankingexpression.parser.ParseException;
import com.yahoo.searchlib.rankingexpression.rule.Arguments;
import com.yahoo.searchlib.rankingexpression.rule.ExpressionNode;
import com.yahoo.searchlib.rankingexpression.rule.FunctionReferenceContext;
import com.yahoo.searchlib.rankingexpression.rule.NameNode;
import com.yahoo.searchlib.rankingexpression.rule.ReferenceNode;
import com.yahoo.tensor.TensorType;
import com.yahoo.tensor.evaluation.TypeContext;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.stream.Collectors;

/**
 * A context which only contains type information.
 * This returns empty tensor types (double) for unknown features which are not
 * query, attribute or constant features, as we do not have information about which such
 * features exist (but we know those that exist are doubles).
 *
 * This is not multithread safe.
 *
 * @author bratseth
 */
public class MapEvaluationTypeContext extends FunctionReferenceContext implements TypeContext<Reference> {

    private final Map<Reference, TensorType> featureTypes = new HashMap<>();

    /** For invocation loop detection */
    private final Deque<Reference> currentResolutionCallStack;

    MapEvaluationTypeContext(Collection<ExpressionFunction> functions) {
        super(functions);
        this.currentResolutionCallStack =  new ArrayDeque<>();
    }

    private MapEvaluationTypeContext(Map<String, ExpressionFunction> functions,
                                     Map<String, String> bindings,
                                     Map<Reference, TensorType> featureTypes,
                                     Deque<Reference> currentResolutionCallStack) {
        super(functions, bindings);
        this.featureTypes.putAll(featureTypes);
        this.currentResolutionCallStack = currentResolutionCallStack;
    }

    public void setType(Reference reference, TensorType type) {
        featureTypes.put(reference, type);
    }

    @Override
    public TensorType getType(String reference) {
        throw new UnsupportedOperationException("Not able to parse gereral references from string form");
    }

    @Override
    public TensorType getType(Reference reference) {
        // A reference to a macro argument?
        if (currentResolutionCallStack.contains(reference))
            throw new IllegalArgumentException("Invocation loop: " +
                                               currentResolutionCallStack.stream().map(Reference::toString).collect(Collectors.joining(" -> ")) +
                                               " -> " + reference);
        currentResolutionCallStack.addLast(reference);

        try {
            Optional<String> binding = boundIdentifier(reference);
            if (binding.isPresent()) {
                try {
                    // This is not pretty, but changing to bind expressions rather
                    // than their string values requires deeper changes
                    return new RankingExpression(binding.get()).type(this);
                } catch (ParseException e) {
                    throw new IllegalArgumentException(e);
                }
            }

            // A reference to an attribute, query or constant feature?
            if (FeatureNames.isSimpleFeature(reference)) {
                // The argument may be a local identifier bound to the actual value
                String argument = reference.simpleArgument().get();
                reference = Reference.simple(reference.name(), bindings.getOrDefault(argument, argument));
                return featureTypes.getOrDefault(reference, defaultTypeOf(reference));
            }

            // A reference to a function?
            Optional<ExpressionFunction> function = functionInvocation(reference);
            if (function.isPresent()) {
                return function.get().getBody().type(this.withBindings(bind(function.get().arguments(), reference.arguments())));
            }

            // A reference to a feature which returns a tensor?
            Optional<TensorType> featureTensorType = tensorFeatureType(reference);
            if (featureTensorType.isPresent()) {
                return featureTensorType.get();
            }

            // We do not know what this is - since we do not have complete knowledge abut the match features
            // in Java we must assume this is a match feature and return the double type - which is the type of all
            // all match features
            return TensorType.empty;
        }
        finally {
            currentResolutionCallStack.removeLast();
        }
    }

    /**
     * Returns the default type for this simple feature, or nullif it does not have a default
     */
    public TensorType defaultTypeOf(Reference reference) {
        if ( ! FeatureNames.isSimpleFeature(reference))
            throw new IllegalArgumentException("This can only be called for simple references, not " + reference);
        if (reference.name().equals("query")) // we do not require all query features to be declared, only non-doubles
            return TensorType.empty;
        return null;
    }

    /**
     * Returns the binding if this reference is a simple identifier which is bound in this context.
     * Returns empty otherwise.
     */
    private Optional<String> boundIdentifier(Reference reference) {
        if ( ! reference.arguments().isEmpty()) return Optional.empty();
        if ( reference.output() != null) return Optional.empty();
        return Optional.ofNullable(bindings.get(reference.name()));
    }

    private Optional<ExpressionFunction> functionInvocation(Reference reference) {
        if (reference.output() != null) return Optional.empty();
        ExpressionFunction function = functions().get(reference.name());
        if (function == null) return Optional.empty();
        if (function.arguments().size() != reference.arguments().size()) return Optional.empty();
        return Optional.of(function);
    }

    /**
     * There are two features which returns the (non-empty) tensor type: tensorFromLabels and tensorFromWeightedSet.
     * This returns the type of those features if this is a reference to either of them, or empty otherwise.
     */
    private Optional<TensorType> tensorFeatureType(Reference reference) {
        if ( ! reference.name().equals("tensorFromLabels") && ! reference.name().equals("tensorFromWeightedSet"))
            return Optional.empty();

        if (reference.arguments().size() != 1 && reference.arguments().size() != 2)
            throw new IllegalArgumentException(reference.name() + " must have one or two arguments");

        ExpressionNode arg0 = reference.arguments().expressions().get(0);
        if ( ! ( arg0 instanceof ReferenceNode) || ! FeatureNames.isSimpleFeature(((ReferenceNode)arg0).reference()))
            throw new IllegalArgumentException("The first argument of " + reference.name() +
                                               " must be a simple feature, not " + arg0);

        String dimension;
        if (reference.arguments().size() > 1) {
            ExpressionNode arg1 = reference.arguments().expressions().get(1);
            if ( ( ! (arg1 instanceof ReferenceNode) || ! (((ReferenceNode)arg1).reference().isIdentifier()))
                 &&
                 ( ! (arg1 instanceof NameNode)))
                throw new IllegalArgumentException("The second argument of " + reference.name() +
                                                   " must be a dimension name, not " + arg1);
            dimension = reference.arguments().expressions().get(1).toString();
        }
        else { // default
            dimension = ((ReferenceNode)arg0).reference().arguments().expressions().get(0).toString();
        }
        return Optional.of(new TensorType.Builder().mapped(dimension).build());
    }

    /** Binds the given list of formal arguments to their actual values */
    private Map<String, String> bind(List<String> formalArguments,
                                     Arguments invocationArguments) {
        Map<String, String> bindings = new HashMap<>(formalArguments.size());
        for (int i = 0; i < formalArguments.size(); i++) {
            String identifier = invocationArguments.expressions().get(i).toString();
            identifier = super.bindings.getOrDefault(identifier, identifier);
            bindings.put(formalArguments.get(i), identifier);
        }
        return bindings;
    }

    public Map<Reference, TensorType> featureTypes() {
        return Collections.unmodifiableMap(featureTypes);
    }

    @Override
    public MapEvaluationTypeContext withBindings(Map<String, String> bindings) {
        if (bindings.isEmpty() && this.bindings.isEmpty()) return this;
        return new MapEvaluationTypeContext(functions(), bindings, featureTypes, currentResolutionCallStack);
    }

}
