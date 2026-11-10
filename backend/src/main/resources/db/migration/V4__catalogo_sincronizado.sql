-- La web de origen puede tener productos con el mismo nombre (por ejemplo, uno inactivo y otro activo),
-- así que el nombre único se exige solo a los productos cargados a mano.

CREATE UNIQUE INDEX products_manual_name_uq ON products (tenant_id, lower(name)) WHERE external_id IS NULL;
