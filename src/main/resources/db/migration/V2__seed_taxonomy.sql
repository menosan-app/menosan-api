-- V2__seed_taxonomy.sql — GENERATED from src/main/resources/taxonomy.json (version 1).
-- Do not edit by hand. See TaxonomySeedTest. Append-only: change the taxonomy in a new migration.

INSERT INTO waste_subcategories (code, category, label, examples, avoidable, sort_order) VALUES
  ('BIO_FOOD_LEFTOVERS', 'BIODEGRADABLE', 'Leftover cooked food', ARRAY['leftover rice', 'leftover ulam', 'plate waste'], true, 1),
  ('BIO_SPOILED_FOOD', 'BIODEGRADABLE', 'Spoiled or expired food', ARRAY['rotten vegetables', 'expired bread', 'sour milk'], true, 2),
  ('BIO_PEELS_SCRAPS', 'BIODEGRADABLE', 'Fruit & vegetable peels/scraps', ARRAY['banana peels', 'onion skins', 'fish scales', 'eggshells'], false, 3),
  ('BIO_YARD_WASTE', 'BIODEGRADABLE', 'Yard & garden waste', ARRAY['dry leaves', 'grass', 'twigs'], false, 4),
  ('BIO_OTHER', 'BIODEGRADABLE', 'Other biodegradable', ARRAY['coconut husk', 'other biodegradable'], false, 5),
  ('REC_PET_BOTTLES', 'RECYCLABLE', 'Plastic bottles', ARRAY['water bottle', 'softdrink PET bottle', 'juice bottle'], true, 1),
  ('REC_RIGID_PLASTICS', 'RECYCLABLE', 'Rigid plastic containers', ARRAY['ice cream tub', 'detergent bottle', 'food tub'], true, 2),
  ('REC_PAPER_CARDBOARD', 'RECYCLABLE', 'Paper & cardboard', ARRAY['boxes', 'receipts', 'flyers', 'newspapers'], true, 3),
  ('REC_GLASS', 'RECYCLABLE', 'Glass bottles & jars', ARRAY['softdrink bottle', 'sauce jar'], true, 4),
  ('REC_METAL_CANS', 'RECYCLABLE', 'Metal cans', ARRAY['sardine can', 'corned beef can', 'softdrink can'], false, 5),
  ('REC_OTHER', 'RECYCLABLE', 'Other recyclable', ARRAY['other recyclable'], false, 6),
  ('RES_SACHETS', 'RESIDUAL', 'Sachets & small packets', ARRAY['shampoo sachet', '3-in-1 coffee', 'condiment packet', 'detergent sachet'], true, 1),
  ('RES_PLASTIC_BAGS', 'RESIDUAL', 'Plastic bags', ARRAY['sando bag', 'labo bag', 'ice bag'], true, 2),
  ('RES_SNACK_WRAPPERS', 'RESIDUAL', 'Snack & candy wrappers', ARRAY['chichirya pack', 'biscuit wrapper', 'candy wrapper'], true, 3),
  ('RES_STYROFOAM', 'RESIDUAL', 'Styrofoam & takeout containers', ARRAY['styro box', 'clamshell', 'takeout cup'], true, 4),
  ('RES_DISPOSABLES', 'RESIDUAL', 'Disposable cutlery, cups & straws', ARRAY['plastic spoon', 'straw', 'plastic cup'], true, 5),
  ('RES_TISSUE', 'RESIDUAL', 'Tissue & paper towels', ARRAY['tissue', 'table napkin', 'wet wipes'], true, 6),
  ('RES_DIAPERS_SANITARY', 'RESIDUAL', 'Diapers & sanitary products', ARRAY['disposable diaper', 'sanitary pad'], false, 7),
  ('RES_OTHER', 'RESIDUAL', 'Other residual', ARRAY['other residual'], false, 8),
  ('SPC_BATTERIES', 'SPECIAL', 'Batteries', ARRAY['AA battery', 'button cell'], false, 1),
  ('SPC_ELECTRONICS', 'SPECIAL', 'Electronic waste', ARRAY['charger', 'cable', 'old phone'], false, 2),
  ('SPC_BULBS', 'SPECIAL', 'Light bulbs & tubes', ARRAY['fluorescent tube', 'light bulb'], false, 3),
  ('SPC_MEDICAL', 'SPECIAL', 'Medical waste', ARRAY['expired medicine', 'syringe', 'used mask'], false, 4),
  ('SPC_CHEMICAL', 'SPECIAL', 'Chemical containers', ARRAY['paint can', 'insecticide', 'aerosol can'], false, 5),
  ('SPC_OTHER', 'SPECIAL', 'Other special waste', ARRAY['other special waste'], false, 6);
