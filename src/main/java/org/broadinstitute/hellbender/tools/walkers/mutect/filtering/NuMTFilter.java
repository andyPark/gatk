package org.broadinstitute.hellbender.tools.walkers.mutect.filtering;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import org.apache.commons.math3.distribution.PoissonDistribution;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;


public class NuMTFilter extends HardAlleleFilter<Integer> {
    private static final double LOWER_BOUND_PROB = .01;
    private final int maxAltDepthCutoff;

    public NuMTFilter(final double medianAutosomalCoverage, final double maxNuMTCopies){
        if (maxNuMTCopies > 0) {
            final PoissonDistribution autosomalCoverage = new PoissonDistribution(medianAutosomalCoverage * maxNuMTCopies / 2.0);
            maxAltDepthCutoff = autosomalCoverage.inverseCumulativeProbability(1 - LOWER_BOUND_PROB);
        } else {
            maxAltDepthCutoff = 0;
        }
    }

    @Override
    public ErrorType errorType() { return ErrorType.NON_SOMATIC; }

    @Override
    public Predicate<Genotype> checkPreconditions() {
        return Genotype::hasAD;
    }

    public List<Integer> getData(Genotype g) {
        return Arrays.stream(g.getAD()).boxed().collect(Collectors.toList());
    }

    @Override
    public Map<Allele, Boolean> areAllelesArtifacts(final LinkedHashMap<Allele, List<Integer>> dataByAllele, final VariantContext vc, final Mutect2FilteringEngine filteringEngine, ReferenceContext referenceContext) {
        return dataByAllele.entrySet().stream()
                .filter(entry -> !entry.getKey().isSymbolic() && !vc.getReference().equals(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream().max(Integer::compare).orElse(0) < maxAltDepthCutoff));
    }

    @Override
    public String filterName() {
        return GATKVCFConstants.POSSIBLE_NUMT_FILTER_NAME;
    }

    @Override
    protected List<String> requiredAnnotations() { return Collections.emptyList(); }

}
