package org.siloserver.silo.tv.ui.screens.library

import org.siloserver.silo.catalog.scopeLibrarySection
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogContinuationV2

internal suspend fun scopeTvLibrarySection(
    section: ResolvedSection,
    mediaScope: String?,
    loadPage: suspend (CatalogContinuationV2?) -> ApiResult<CatalogResponse>,
) = scopeLibrarySection(section, mediaScope, loadPage)
