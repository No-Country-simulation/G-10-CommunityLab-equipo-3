package com.nocountry.simulation.communitylab.application.dtos;

import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;

// What load() returns: the package plus the etag of that same read, so replace() can't use an etag from another read.
public record StoredPackage(
        AssetPackage pkg,
        String etag
) {

    // Why: the default record toString would print every post (authorName, content) and the etag (RNF-04).
    @Override
    public String toString() {
        return "StoredPackage{" +
                "batchId='" + pkg.batchId() + '\'' +
                ", source=" + pkg.source() +
                ", etag='****'" +
                '}';
    }
}
