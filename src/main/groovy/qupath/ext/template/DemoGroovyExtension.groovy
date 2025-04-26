package qupath.ext.template

import javafx.beans.value.ChangeListener
import javafx.concurrent.Task
import javafx.scene.control.Alert.AlertType
import qupath.lib.common.Version
import qupath.lib.gui.extensions.QuPathExtension
import qupath.lib.objects.PathObject
import org.apache.commons.math3.ml.distance.EuclideanDistance
import qupath.lib.gui.QuPathGUI
import javafx.stage.Modality
import javafx.application.Platform
import qupath.lib.objects.classes.PathClass
import java.awt.Color
import javafx.stage.FileChooser
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.geometry.Insets

/**
 *
 * A QuPath extension that demonstrates a 'Cell Search Engine' with:
 *  - Quick Search (Morphology, Marker, Combined, Neighborhood)
 *  - Comprehensive Search (CSV-based clustering)
 *  phenotype finder
 *

 */
class DemoGroovyExtension implements QuPathExtension {
	private static List<Map<String, String>> cachedCSVRows = null
	private static String cachedCSVPath = null

	String name = "Cell Search Engine"
	String description = "Offers quick and comprehensive cell similarity searches."
	Version QuPathVersion = Version.parse("v0.4.0")

	@Override
	void installExtension(QuPathGUI qupath) {
		def mainMenu = qupath.getMenu("Extensions>" + name, true)

		// --- QUICK CELL SEARCH ---
		def quickSearchMenu = new Menu("Quick Cell Search")



		def UnifiedSearchItem = new MenuItem("Unified Search")
		UnifiedSearchItem.setOnAction(e -> { runUnifiedSearch(qupath) })
		quickSearchMenu.getItems().addAll(UnifiedSearchItem)

		// --- COMPREHENSIVE SEARCH ---
		def comprehensiveMenu = new Menu("Comprehensive Search")


		def csvClusterItem = new MenuItem("Community Search")
		csvClusterItem.setOnAction(e -> runCSVClusterSearch(qupath))
		comprehensiveMenu.getItems().add(csvClusterItem)

		def phenotypeFinderItem = new MenuItem("Phenotype Finder")
		phenotypeFinderItem.setOnAction(e -> runPhenotypeFinder(qupath))
		comprehensiveMenu.getItems().add(phenotypeFinderItem)

		def resetRegionItem = new MenuItem("Reset Region Highlights")
		resetRegionItem.setOnAction(e -> resetRegionHighlights(qupath))


		mainMenu.getItems().addAll(quickSearchMenu, comprehensiveMenu, resetRegionItem)
	}

// Unified Search that intelligently switches between Neighborhood and Multi-Query modes
// depending on the number of selected cells and chosen operation

	private static HBox partitionCheckboxes(List<CheckBox> checkboxes, int numCols) {
		int per = (int)Math.ceil(checkboxes.size()/numCols)
		def cols = []
		(0..<numCols).each { i ->
			int s = i*per, e = Math.min(s+per, checkboxes.size())
			def vb = new VBox(5)
			checkboxes.subList(s,e).each { vb.children.add(it) }
			cols << vb
		}
		def hb = new HBox(10)
		cols.each { hb.children.add(it) }
		return hb
	}
// Full logic for Neighborhood Search
    private static void runNeighborhoodSearch(QuPathGUI qupath, PathObject targetCell, List<CheckBox> markerCheckboxes, List<CheckBox> morphCbs, List<CheckBox> surroundCheckboxes, TextField tfRadius, TextField tfTopN, CheckBox cbLocalDensity, ProgressBar progressBar, Label progressLabel) {
        def imageData = qupath.getImageData()
        def hierarchy = imageData.getHierarchy()
        def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
        def pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
        double radiusMicrons = tfRadius.getText().toDouble()
        double radiusPixels = radiusMicrons / pixelSize

        if (!cbLocalDensity.isSelected()) {

			runNeighborhoodSearchAfterDensity(qupath, targetCell, markerCheckboxes, morphCbs, surroundCheckboxes, tfRadius, tfTopN)
			return
        }

        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Computing local density...")
                int total = allCells.size()
                def spatialIndex = [:].withDefault { [] }
                allCells.each { cell ->
                    def r = cell.getROI()
                    def gx = (int)(r.getCentroidX() / radiusPixels)
                    def gy = (int)(r.getCentroidY() / radiusPixels)
                    spatialIndex["${gx}_${gy}"] << cell
                }
                allCells.eachWithIndex { cell, i ->
                    def r = cell.getROI()
                    def cx = r.getCentroidX()
                    def cy = r.getCentroidY()
                    def gx = (int)(cx / radiusPixels)
                    def gy = (int)(cy / radiusPixels)
                    def count = 0
                    for (dx in -1..1) {
                        for (dy in -1..1) {
                            def key = "${gx + dx}_${gy + dy}"
                            spatialIndex[key].each { neighbor ->
                                def r2 = neighbor.getROI()
                                def dx2 = r2.getCentroidX() - cx
                                def dy2 = r2.getCentroidY() - cy
                                if ((dx2 * dx2 + dy2 * dy2) <= radiusPixels * radiusPixels)
                                    count++
                            }
                        }
                    }
                    count -= 1 // Exclude self
                    cell.getMeasurementList().putMeasurement("Local Density (r=${(int)radiusPixels})", count)
                    updateProgress(i + 1, total)
                    updateMessage("Processed ${i + 1} / ${total} cells")
                }
                Platform.runLater {
                    runNeighborhoodSearchAfterDensity(qupath, targetCell, markerCheckboxes, morphCbs, surroundCheckboxes, tfRadius, tfTopN)
                    progressLabel.textProperty().unbind()
                    progressLabel.setText("✅ Done")
                }
                return null
            }
        }
        progressBar.progressProperty().bind(task.progressProperty())
        progressLabel.textProperty().bind(task.messageProperty())
        Thread t = new Thread(task)
        t.setDaemon(true)
        t.start()
    }

	private static void runNeighborhoodSearchAfterDensity(QuPathGUI qupath, PathObject targetCell, List<CheckBox> markerCheckboxes, List<CheckBox> morphCbs, List<CheckBox> surroundCheckboxes, TextField tfRadius, TextField tfTopN) {

		def imageData = qupath.getImageData()
		def hierarchy = imageData.getHierarchy()
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		def pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
		double radiusMicrons = tfRadius.getText().toDouble()
		double radiusPixels = radiusMicrons / pixelSize


		int topN = tfTopN.getText().toInteger()

		def roi = targetCell.getROI()
		def centerX = roi.getCentroidX()
		def centerY = roi.getCentroidY()

		def features = []
		markerCheckboxes.findAll { it.isSelected() }.each { features << "Cell: ${it.getText()} mean" }
		morphCbs.findAll { it.isSelected() }.each { features << "Cell: ${it.getText()}" }

		boolean useSpatial = surroundCheckboxes.any { it.isSelected() }
		def distCalc = new EuclideanDistance()

		if (useSpatial) {
			def selectedMarkers = surroundCheckboxes.findAll { it.isSelected() }*.getText().collect { "Cell: ${it} mean" }
			def cellCoordinates = allCells.collectEntries { cell ->
				def r = cell.getROI()
				[(cell): [r.getCentroidX(), r.getCentroidY()]]
			}
			def spatialIndex = [:].withDefault { [] }
			cellCoordinates.each { cell, coord ->
				def gx = (int)(coord[0] / radiusPixels)
				def gy = (int)(coord[1] / radiusPixels)
				spatialIndex["${gx}_${gy}"] << cell
			}
			def getNeighbors = { coord ->
				def gx = (int)(coord[0] / radiusPixels)
				def gy = (int)(coord[1] / radiusPixels)
				def neighbors = [] as Set
				for (dx in -1..1) {
					for (dy in -1..1) {
						neighbors.addAll(spatialIndex["${gx+dx}_${gy+dy}"])
					}
				}
				neighbors.findAll {
					def xy = cellCoordinates[it]
					def dx = xy[0] - coord[0]
					def dy = xy[1] - coord[1]
					(dx*dx + dy*dy) <= radiusPixels*radiusPixels
				}
			}

			def targetNeighborhood = getNeighbors([centerX, centerY])
			def avgVec = selectedMarkers.collect { marker ->
				def vals = targetNeighborhood.collect { c -> c.getMeasurementList().getMeasurementValue(marker) ?: 0.0 }
				vals.sum() / vals.size()
			}

			def cellMarkerMap = allCells.collectEntries { cell ->
				[(cell): selectedMarkers.collect { m -> cell.getMeasurementList().getMeasurementValue(m) ?: 0.0 }]
			}

			def scored = allCells.findAll { it != targetCell }.collect { cell ->
				def coord = cellCoordinates[cell]
				def neighborhood = getNeighbors(coord)
				def avg = selectedMarkers.indices.collect { i ->
					def values = neighborhood.collect { n -> cellMarkerMap[n][i] }
					values.sum() / values.size()
				}
				[cell, distCalc.compute(avgVec as double[], avg as double[])]
			}

			scored.sort { it[1] }
			def topCells = scored.take(topN).collect { it[0] }
            def className = PathClass.fromString("Neighborhood-Run-${System.currentTimeMillis() % 100000}")
            topCells.each { it.setPathClass(className) }


            hierarchy.getSelectionModel().setSelectedObjects([targetCell] + topCells, targetCell)
		} else if (!features.isEmpty()) {
			def targetVec = features.collect { targetCell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
			def scored = allCells.findAll { it != targetCell }.collect { cell ->
				def vec = features.collect { cell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
				[cell, distCalc.compute(targetVec as double[], vec as double[])]
			}
			scored.sort { it[1] }
			def topCells = scored.take(topN).collect { it[0] }
            def className = PathClass.fromString("Neighborhood-Run-${System.currentTimeMillis() % 100000}")
            topCells.each { it.setPathClass(className) }


            hierarchy.getSelectionModel().setSelectedObjects([targetCell] + topCells, targetCell)
		} else {
			def nearby = allCells.findAll {
				def dx = it.getROI().getCentroidX() - centerX
				def dy = it.getROI().getCentroidY() - centerY
				(dx*dx + dy*dy) <= radiusPixels*radiusPixels
			}
            def className = PathClass.fromString("Neighborhood-Run-${System.currentTimeMillis() % 100000}")
            nearby.each { it.setPathClass(className) }


            hierarchy.getSelectionModel().setSelectedObjects([targetCell] + nearby, targetCell)
		}

		Platform.runLater {
			hierarchy.fireHierarchyChangedEvent(null)
			qupath.getViewer().repaint()
		}
	}

	private static void runMultiQuerySearch(QuPathGUI qupath, List<PathObject> selected, List<CheckBox> markerCheckboxes, List<CheckBox> morphCbs, List<CheckBox> surroundCheckboxes, List<CheckBox> allOps, TextField tfRadius, TextField tfTopN, TextField tfWeight, ProgressBar progressBar, Label progressLabel) {

		def imageData = qupath.getImageData()
		def hierarchy = imageData.getHierarchy()
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		double radiusPx = tfRadius.getText().toDouble() / imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
		int limit = tfTopN.getText().toInteger()
		double k = tfWeight.getText().toDouble()
		def useSpatial = surroundCheckboxes.any { it.isSelected() }

		Task<Void> task = new Task<Void>() {
			List<PathObject> resultCells = []
			@Override protected Void call() {
				try {
					def allMap = [:].withDefault { [] }
					if (useSpatial) {
						allCells.each {
							def r = it.getROI()
							def key = "${(int)(r.getCentroidX()/radiusPx)}_${(int)(r.getCentroidY()/radiusPx)}"
							allMap[key] << it
						}
					}

					def extract = { cell ->
						def vec = []
						markerCheckboxes.findAll { it.isSelected() }.each {
							vec << (cell.getMeasurementList().getMeasurementValue("Cell: ${it.getText()} mean") ?: 0.0)
						}
						morphCbs.findAll { it.isSelected() }.each {
							vec << (cell.getMeasurementList().getMeasurementValue("Cell: ${it.getText()}") ?: 0.0)
						}
						if (useSpatial) {
							def cx = cell.getROI().getCentroidX()
							def cy = cell.getROI().getCentroidY()
							def gx = (int)(cx / radiusPx)
							def gy = (int)(cy / radiusPx)
							def neighbors = []
							for (dx in -1..1) {
								for (dy in -1..1) {
									def key = "${gx+dx}_${gy+dy}"
									allMap[key]?.each {
										def r = it.getROI()
										def dx2 = r.getCentroidX() - cx
										def dy2 = r.getCentroidY() - cy
										if (dx2*dx2 + dy2*dy2 <= radiusPx*radiusPx && it != cell)
											neighbors << it
									}
								}
							}
							surroundCheckboxes.findAll { it.isSelected() }.each {
								def values = neighbors.collect { n -> n.getMeasurementList().getMeasurementValue("Cell: ${it.getText()} mean") ?: 0.0 }
								vec << (values ? values.sum() / values.size() : 0.0)
							}
						}
						return vec as double[]
					}

					def distCalc = new EuclideanDistance()
					def vectors = allCells.collectEntries { [(it): extract(it)] }

					if (allOps.find { it.getText() == "Competitive Boost" && it.isSelected() }) {
						def vecT = vectors[selected[0]]
						def vecP = vectors[selected[1]]
						def vecN = vectors[selected[2]]
						def pq = new PriorityQueue<>(limit, { a, b -> b.value <=> a.value } as Comparator)
						allCells.each { c ->
							if (c == selected[0]) return
							double dT = distCalc.compute(vecT, vectors[c])
							double dP = distCalc.compute(vecP, vectors[c])
							double dN = distCalc.compute(vecN, vectors[c])
							double score = dT + k * dP - k * dN
							if (pq.size() < limit) pq.add(new AbstractMap.SimpleEntry<>(c, score))
							else if (score < pq.peek().value) { pq.poll(); pq.add(new AbstractMap.SimpleEntry<>(c, score)) }
						}
						resultCells = pq.toArray().toList().sort { a, b -> a.value <=> b.value }.collect { it.key }
					} else {
						def neighborSets = []
						selected.eachWithIndex { center, idx ->
							updateMessage("Processing ${idx+1}/${selected.size()}...")
							updateProgress(idx, selected.size())
							def pq = new PriorityQueue<>(limit, { a, b -> b.value <=> a.value } as Comparator)
							def vecC = vectors[center]
							allCells.each { c ->
								if (c == center) return
								def score = distCalc.compute(vecC, vectors[c])
								if (pq.size() < limit) pq.add(new AbstractMap.SimpleEntry<>(c, score))
								else if (score < pq.peek().value) { pq.poll(); pq.add(new AbstractMap.SimpleEntry<>(c, score)) }
							}
							neighborSets << pq.toArray().toList().sort { a, b -> a.value <=> b.value }.collect { it.key } as Set
						}
						if (allOps.find { it.getText() == "Union" && it.isSelected() }) {
							resultCells = neighborSets.flatten().collect { it as PathObject }
						} else if (allOps.find { it.getText() == "Intersection" && it.isSelected() }) {
							resultCells = neighborSets.inject(neighborSets[0]) { a, b -> a.intersect(b) }.toList()
						} else if (allOps.find { it.getText() == "Subtract" && it.isSelected() }) {
							def base = neighborSets[0]
							def subtract = neighborSets[1..-1].flatten() as Set
							resultCells = (base - subtract).toList()
						} else if (allOps.find { it.getText() == "Contrastive" && it.isSelected() }) {
							resultCells = (neighborSets[0] - neighborSets[1]).collect { it as PathObject }
						}
					}

					resultCells.each { it.setPathClass(PathClass.fromString("Multi-Query-Search")) }
					Platform.runLater {
						hierarchy.getSelectionModel().setSelectedObjects(resultCells, null)
						progressLabel.textProperty().unbind()
						progressLabel.setText("✅ Done: ${resultCells.size()} cells")
					}
				} catch (Exception ex) {
					ex.printStackTrace()
					Platform.runLater {
						progressLabel.textProperty().unbind()
						progressLabel.setText("❌ Failed: ${ex.message}")
					}
					throw ex
				}
				return null
			}
		}

		progressBar.progressProperty().bind(task.progressProperty())
		progressLabel.textProperty().bind(task.messageProperty())
		Thread thread = new Thread(task); thread.setDaemon(true); thread.start()
	}
// Unified Dispatcher UI
	private static void runUnifiedSearch(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			new Alert(Alert.AlertType.WARNING, "No image data available.").show()
			return
		}
		def hierarchy = imageData.getHierarchy()
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		if (allCells.isEmpty()) {
			new Alert(Alert.AlertType.WARNING, "No cell detections found.").show()
			return
		}

		def measurementNames = allCells[0].getMeasurementList().getMeasurementNames()

		def markerLabels = measurementNames.findAll { it.startsWith("Cell: ") && it.endsWith(" mean") }
				.collect { it.replace("Cell: ", "").replace(" mean", "") }

		def markerCheckboxes = markerLabels.collect { new CheckBox(it) }
		def cbMarkerSelectAll = new CheckBox("Select All Markers")
		cbMarkerSelectAll.setOnAction { markerCheckboxes.each { it.setSelected(cbMarkerSelectAll.isSelected()) } }

		def morphCbs = ["Area", "Perimeter", "Circularity", "Max caliper", "Min caliper", "Eccentricity"]
				.collect { new CheckBox(it) }
		def cbMorphSelectAll = new CheckBox("Select All Morphological")
		cbMorphSelectAll.setOnAction { morphCbs.each { it.setSelected(cbMorphSelectAll.isSelected()) } }

		def surroundCheckboxes = markerLabels.collect { new CheckBox(it) }
		def cbSurroundSelectAll = new CheckBox("Select All Neighborhood Markers")
		cbSurroundSelectAll.setOnAction { surroundCheckboxes.each { it.setSelected(cbSurroundSelectAll.isSelected()) } }

        def cbLocalDensity = new CheckBox("Use Local Density")
		cbLocalDensity.setSelected(false)

        def cbUnion = new CheckBox("Union")
		def cbIntersection = new CheckBox("Intersection")
		def cbSubtract = new CheckBox("Subtract")
		def cbContrastive = new CheckBox("Contrastive")
		def cbCompetitive = new CheckBox("Competitive Boost")
		def allOps = [cbUnion, cbIntersection, cbSubtract, cbContrastive, cbCompetitive]
		def enforceSingleOp = { changed -> allOps.each { if (it != changed) it.setSelected(false) } }
		allOps.each { cb -> cb.selectedProperty().addListener({ obs, oldV, newV -> if (newV) enforceSingleOp(cb) } as ChangeListener) }

		TextField tfTopN = new TextField("4000")
		TextField tfRadius = new TextField("50")
		TextField tfWeight = new TextField("1.0")
		ProgressBar progressBar = new ProgressBar(0.0)
		Label progressLabel = new Label("Idle")

		Button btnRun = new Button("Run")
		Button btnExport = new Button("Export CSV")
		Button btnReset = new Button("Reset")
		Button btnClose = new Button("Close")



		Stage dialogStage = new Stage()

		btnRun.setOnAction {
			def selected = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }

			if (selected.isEmpty()) {
				new Alert(Alert.AlertType.WARNING, "Please select at least one cell!").show()
				return
			}

			if (selected.size() == 1 && !allOps.any { it.isSelected() }) {
				// Run standard neighborhood search with full parameter set
				runNeighborhoodSearch(
						qupath, selected[0],
						markerCheckboxes, morphCbs, surroundCheckboxes,
						tfRadius, tfTopN, cbLocalDensity,
						progressBar, progressLabel
				)
			} else if (selected.size() >= 2 && allOps.any { it.isSelected() }) {
				// Multi-query logic stays unchanged
				runMultiQuerySearch(
						qupath, selected as List,
						markerCheckboxes, morphCbs, surroundCheckboxes,
						allOps, tfRadius, tfTopN, tfWeight,
						progressBar, progressLabel
				)
			} else {
				new Alert(Alert.AlertType.WARNING, "Please check if your selection and operation match.").show()
			}
		}

		btnReset.setOnAction {

			allCells.each { it.setPathClass(null) }
			hierarchy.getSelectionModel().clearSelection()


			Platform.runLater {
				hierarchy.fireHierarchyChangedEvent(null)
				qupath.getViewer().repaint()
			}
		}
		btnClose.setOnAction { dialogStage.close() }

		btnExport.setOnAction {
			def sel = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (sel.isEmpty()) { new Alert(Alert.AlertType.WARNING, "No cells to export.").show(); return }
			def chooser = new FileChooser()
			chooser.setTitle("Export CSV")
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"))
			def file = chooser.showSaveDialog(dialogStage)
			if (file) {
				file.withPrintWriter { pw ->
					pw.println("CentroidX,CentroidY")
					sel.each {
						def r = it.getROI()
						pw.println("${r.getCentroidX()},${r.getCentroidY()}")
					}
				}
				new Alert(Alert.AlertType.INFORMATION, "Exported ${sel.size()} cells.").show()
			}
		}

		VBox layout = new VBox(10,
				new VBox(5, new Label("Marker Selections:"), cbMarkerSelectAll, partitionCheckboxes(markerCheckboxes, 4)),
				new VBox(5, new Label("Morphological Features:"), cbMorphSelectAll, partitionCheckboxes(morphCbs, 3)),
				new VBox(5, new Label("Neighborhood Markers:"), cbSurroundSelectAll, partitionCheckboxes(surroundCheckboxes, 4)),
				new HBox(10, new Label("Top N:"), tfTopN, new Label("Radius (µm):"), tfRadius, new Label("Weight k:"), tfWeight),
                new VBox(5, new Label("Other Features:"), cbLocalDensity),
                new VBox(5, new Label("Operation:"), cbUnion, cbIntersection, cbSubtract, cbContrastive, cbCompetitive),
				new HBox(10, btnRun, btnExport, btnReset, btnClose),
				progressBar, progressLabel
		)
		layout.setPadding(new Insets(20))
		dialogStage.setTitle("Unified Cell Search")
		dialogStage.initOwner(qupath.getStage())
		dialogStage.setScene(new Scene(layout))
		dialogStage.show()
	}


	// --- CSV-BASED CLUSTER SEARCH ---
	static void runCSVClusterSearch(QuPathGUI qupath) {
		Stage stage = new Stage()
		stage.setTitle("CSV Cluster Search")
		stage.initOwner(qupath.getStage()) // No modality!


		// UI Elements
		TextField filePathField = new TextField(); filePathField.setEditable(false)
		ComboBox<String> comboBox = new ComboBox<>()
		comboBox.getItems().addAll("level_1", "level_2", "level_3", "level_4", "level_5", "level_6")
		comboBox.setValue("level_1")

		Slider toleranceSlider = new Slider(1, 50, 10)
		toleranceSlider.setShowTickLabels(true); toleranceSlider.setShowTickMarks(true)
		toleranceSlider.setMajorTickUnit(10); toleranceSlider.setMinorTickCount(4)

		Button browseButton = new Button("Browse CSV")
		Button runButton = new Button("Run")
		runButton.setDisable(true)
		Button resetButton = new Button("Reset Highlights")

		// Layout
		GridPane grid = new GridPane()
		grid.setPadding(new Insets(20))
		grid.setHgap(10); grid.setVgap(10)

		grid.add(new Label("CSV File:"), 0, 0)
		grid.add(filePathField, 1, 0)
		grid.add(browseButton, 2, 0)

		grid.add(new Label("Cluster Level:"), 0, 1)
		grid.add(comboBox, 1, 1)

		grid.add(new Label("Tolerance (px):"), 0, 2)
		grid.add(toleranceSlider, 1, 2, 2, 1)

		grid.add(runButton, 1, 3)
		grid.add(resetButton, 2, 3)

		Scene scene = new Scene(grid)
		stage.setScene(scene)
		stage.show()

		// Internal data cache
		def rows = []
		def header = []
		File csvFile = null

		// Browse Action
		browseButton.setOnAction({
			FileChooser fileChooser = new FileChooser()
			fileChooser.setTitle("Select CSV File")
			fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"))
			def selected = fileChooser.showOpenDialog(qupath.getStage())
			if (selected != null) {
				filePathField.setText(selected.getAbsolutePath())
				csvFile = selected
				rows.clear()
				header.clear()
				csvFile.withReader { reader ->
					def lines = reader.readLines()
					if (!lines) return
					header.addAll(lines[0].split(","))
					lines[1..-1].each { line ->
						def parts = line.split(",")
						def row = [:]
						for (int i = 0; i < header.size(); i++)
							row[header[i]] = (i < parts.length ? parts[i] : "")
						rows << row
					}
				}
				runButton.setDisable(false)
			}
		})

		resetButton.setOnAction({
			def imageData = qupath.getImageData()
			if (imageData != null) {
				def allCells = imageData.getHierarchy().getDetectionObjects().findAll { it.isCell() }
				allCells.each { it.setPathClass(null) }
				Platform.runLater {
					def viewer = qupath.getViewer()
					def hierarchy = qupath.getImageData().getHierarchy()
					hierarchy.fireHierarchyChangedEvent(null)
					viewer.repaint()

					def alert = new Alert(AlertType.INFORMATION, "✅ Highlights reset.")
					alert.initOwner(qupath.getStage())  // Tie alert to main window
					alert.showAndWait()
				}
			}
		})

		// Run Button Action
		runButton.setOnAction({
			if (!csvFile || rows.isEmpty()) return

			def imageData = qupath.getImageData()
			if (imageData == null) {
				def alert = new Alert(AlertType.WARNING, "⚠️ No image data found.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def hierarchy = imageData.getHierarchy()
			def selected = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selected.isEmpty()) {
				def alert = new Alert(AlertType.WARNING, "⚠️ Please select a cell to run cluster search.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def chosenLevel = comboBox.getValue()
			def tolerance = toleranceSlider.getValue()

// Collect cluster labels from selected cells
			def selectedLabels = [] as Set
			selected.each { cell ->
				def cx = cell.getROI().getCentroidX()
				def cy = cell.getROI().getCentroidY()

				def nearest = rows.min { row ->
					if (!row.x || !row.y) return Double.MAX_VALUE
					def dx = (row.x as double) - cx
					def dy = (row.y as double) - cy
					return dx * dx + dy * dy
				}
				if (nearest != null && nearest[chosenLevel] != null)
					selectedLabels << nearest[chosenLevel]
			}

			if (selectedLabels.isEmpty()) {
				def alert = new Alert(AlertType.WARNING, "No matching clusters found in CSV for selected cells.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

// Find all matching rows with *any* of the selected cluster labels
			def matchingRows = rows.findAll { row -> selectedLabels.contains(row[chosenLevel]) }

			// Spatial bin map
			def binSize = tolerance
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def cellMap = [:].withDefault { [] }
			allCells.each {
				def x = it.getROI().getCentroidX()
				def y = it.getROI().getCentroidY()
				def key = "${(int)(x/binSize)}_${(int)(y/binSize)}"
				cellMap[key] << it
			}

			def matchedCells = [] as Set
			matchingRows.each { row ->
				if (row.x && row.y) {
					def cx = row.x as double
					def cy = row.y as double
					def gx = (int)(cx / binSize)
					def gy = (int)(cy / binSize)
					for (dx in -1..1) {
						for (dy in -1..1) {
							def key = "${gx + dx}_${gy + dy}"
							def group = cellMap[key]
							group.each {
								def dx2 = it.getROI().getCentroidX() - cx
								def dy2 = it.getROI().getCentroidY() - cy
								if ((dx2 * dx2 + dy2 * dy2) <= (tolerance * tolerance))
									matchedCells << it
							}
						}
					}
				}
			}
			def labelStr = selectedLabels.join("_").replaceAll("[^a-zA-Z0-9_]", "_")
			def pathClass = PathClass.fromString("Cluster-${chosenLevel}-${labelStr}")

			matchedCells.each { it.setPathClass(pathClass) }

			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(matchedCells.toList(), null)

			Platform.runLater {
				def viewer = qupath.getViewer()
				hierarchy.fireHierarchyChangedEvent(null)
				viewer.repaint()

				def labelSummary = selectedLabels.join(", ")
				def alert = new Alert(AlertType.INFORMATION,
						"✅ Cluster highlight complete for ${chosenLevel} in [${labelSummary}]\nFound ${matchedCells.size()} cells")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
			}


			// Export matched
			def exportFile = new File(csvFile.getParent(), "matched_cells_${chosenLevel}_${selectedLabels.join('_')}.csv")
			exportFile.withWriter { w ->
				w.write("CellX,CellY\n")
				matchedCells.each {
					def roi = it.getROI()
					w.write("${roi.getCentroidX()},${roi.getCentroidY()}\n")
				}
			}
			println "Exported to: ${exportFile.absolutePath}"
		})
	}


	static void runPhenotypeFinder(QuPathGUI qupath) {
		File selectedCSV = null
		List<Map<String, String>> cachedRows = null
		def makeCoordKey = { double x, double y -> "${Math.round(x)}_${Math.round(y)}" }

		def phenotypeColors = [
				"Leukocytes": new Color(255, 0, 0),
				"B_cells": new Color(0, 128, 255),
				"Myeloid_cells": new Color(255, 165, 0),
				"Lymphocytes": new Color(0, 255, 0),
				"Helper_T_cells": new Color(255, 20, 147),
				"Helper_T_foxp3_cells": new Color(186, 85, 211),
				"Helper_T_GZMB_cells": new Color(0, 139, 139),
				"Cytotoxic_T_cells": new Color(255, 69, 0),
				"Cytotoxic_T_Foxp3_cells": new Color(199, 21, 133),
				"NK": new Color(128, 0, 128),
				"Type1": new Color(0, 206, 209),
				"Dentric cells": new Color(70, 130, 180),
				"M1 macrophages": new Color(255, 140, 0),
				"M2 macrophages": new Color(139, 69, 19),
				"Regulatory T cells": new Color(127, 255, 212),
				"Memory T cells": new Color(173, 255, 47),
				"Stromal COLA1": new Color(154, 205, 50),
				"Stromal CD31": new Color(0, 191, 255),
				"Stromal aSMA": new Color(221, 160, 221),
				"Stromal FAP": new Color(147, 112, 219),
				"Epithelial": new Color(255, 215, 0),
				"Proliferation": new Color(0, 255, 127)
		]

		def imageData = qupath.getImageData()
		if (!imageData) {
			def alert =new Alert(AlertType.ERROR, "❌ No image open in QuPath.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}

		// --- First dialog to upload CSV ---
		Stage uploadStage = new Stage()
		uploadStage.setTitle("Upload Phenotype CSV")
		uploadStage.initModality(Modality.NONE)
		uploadStage.initOwner(qupath.getStage())

		TextField pathField = new TextField(); pathField.setEditable(false)
		Button browseButton = new Button("Browse")
		Button runUploadButton = new Button("Run")
		Button cancelUploadButton = new Button("Cancel")

		HBox fileRow = new HBox(10, pathField, browseButton)
		HBox buttonRow = new HBox(10, runUploadButton, cancelUploadButton)
		VBox layout = new VBox(10, fileRow, buttonRow)
		layout.setPadding(new Insets(20))

		uploadStage.setScene(new Scene(layout))
		uploadStage.show()

		FileChooser chooser = new FileChooser()
		chooser.setTitle("Select CSV File")
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"))

		browseButton.setOnAction {
			File file = chooser.showOpenDialog(qupath.getStage())
			if (file != null) {
				selectedCSV = file
				pathField.setText(file.getAbsolutePath())
			}
		}

		cancelUploadButton.setOnAction {
			uploadStage.close()
		}

		runUploadButton.setOnAction {
			if (selectedCSV == null || !selectedCSV.exists()) {
				def alert =new Alert(AlertType.WARNING, "Please select a valid CSV file.")
				alert.initOwner(qupath.getStage())
				alert.show()
				return
			}
			cachedRows = []
			selectedCSV.withReader { reader ->
				def lines = reader.readLines()
				def headers = lines[0].split(",").collect { it.trim() }
				lines[1..-1].each { line ->
					def parts = line.split(",")
					def row = [:]
					headers.eachWithIndex { h, i ->
						row[h] = (i < parts.size()) ? parts[i].trim() : ""
					}
					cachedRows << (row as Map<String, String>)
				}
			}
			uploadStage.close()
			showPhenotypeDialog(qupath, imageData, cachedRows, makeCoordKey, phenotypeColors)
		}
	}

	static void showPhenotypeDialog(QuPathGUI qupath, def imageData, List<Map<String, String>> cachedRows, Closure makeCoordKey, Map<String, Color> phenotypeColors) {
		Stage stage = new Stage()
		stage.setTitle("Select Phenotype")
		stage.initModality(Modality.NONE)
		stage.initOwner(qupath.getStage())

		ComboBox<String> phenotypeCombo = new ComboBox<>()
		phenotypeCombo.getItems().addAll(phenotypeColors.keySet().toList().sort())
		phenotypeCombo.setValue("Epithelial")

		ComboBox<String> tumorCombo = new ComboBox<>()
		tumorCombo.getItems().addAll("Yes", "No", "Ignore")
		tumorCombo.setValue("Ignore")

		Button runButton = new Button("Run")
		Button cancelButton = new Button("Close")

		GridPane grid = new GridPane()
		grid.setHgap(10)
		grid.setVgap(10)
		grid.setPadding(new Insets(20))
		grid.add(new Label("Phenotype:"), 0, 0)
		grid.add(phenotypeCombo, 1, 0)
		grid.add(new Label("Tumor Filter:"), 0, 1)
		grid.add(tumorCombo, 1, 1)
		grid.add(runButton, 0, 2)
		grid.add(cancelButton, 1, 2)

		stage.setScene(new Scene(grid))
		stage.show()

		cancelButton.setOnAction {
			stage.close()
		}

		runButton.setOnAction {
			def phenotype = phenotypeCombo.getValue()
			def tumorFilter = tumorCombo.getValue()

			def filtered = cachedRows.findAll { row ->
				try {
					def matchPheno = row[phenotype] == "1" || row[phenotype] == "1.0"
					def matchTumor = true
					if (tumorFilter == "Yes")
						matchTumor = row["tumor"]?.toLowerCase() in ["true", "1"]
					else if (tumorFilter == "No")
						matchTumor = row["tumor"]?.toLowerCase() in ["false", "0"]
					return matchPheno && matchTumor
				} catch (Exception e) {
					return false
				}
			}

			def csvKeySet = filtered.collect {
				makeCoordKey(it["Converted X µm"] as double, it["Converted Y µm"] as double)
			}.toSet()

			def allCells = imageData.getHierarchy().getDetectionObjects().findAll { it.isCell() }
			def matched = allCells.findAll {
				def key = makeCoordKey(it.getROI().getCentroidX(), it.getROI().getCentroidY())
				csvKeySet.contains(key)
			}

			def color = phenotypeColors.get(phenotype, Color.RED)
			def pathClass = PathClass.fromString("Phenotype-${phenotype}")
			pathClass.setColor(color.getRed(), color.getGreen(), color.getBlue())

			matched.each { it.setPathClass(pathClass) }
			Platform.runLater {
				def viewer = qupath.getViewer()
				def hierarchy = qupath.getImageData().getHierarchy()
				hierarchy.fireHierarchyChangedEvent(null)
				viewer.repaint()
			}
			def hierarchy = imageData.getHierarchy()
			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(matched, null)
			qupath.getViewer().repaint()

			def alert = new Alert(AlertType.INFORMATION,
					"✅ Highlighted ${matched.size()} cells for '${phenotype}' (Tumor: ${tumorFilter})")
			alert.initOwner(qupath.getStage())
			alert.show()  // Non-blocking
		}
	}

	private static void resetRegionHighlights(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			def alert = new Alert(AlertType.WARNING, "⚠️ No image data available.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}

		def hierarchy = imageData.getHierarchy()
		def annotations = hierarchy.getAnnotationObjects()

		if (annotations.isEmpty()) {
			def alert = new Alert(AlertType.WARNING, "⚠️ No annotation (region) selected.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}

		// Use the first annotation for simplicity
		def annotation = annotations[0]
		def selectedRegion = annotation.getROI()
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }

		def insideRegion = allCells.findAll { cell ->
			selectedRegion.contains(cell.getROI().getCentroidX(), cell.getROI().getCentroidY())
		}

		// Clear highlights
		insideRegion.each { it.setPathClass(null) }

		// ✅ Remove annotation from hierarchy
		hierarchy.removeObject(annotation, false)

		// ✅ Refresh viewer and hierarchy visuals
		Platform.runLater {
			hierarchy.fireHierarchyChangedEvent(null)
			qupath.getViewer().repaint()

			def alert = new Alert(AlertType.INFORMATION,
					"✅ Reset highlights for ${insideRegion.size()} cells and deleted the selected region.")
			alert.initOwner(qupath.getStage())
			alert.show()
		}
	}


}