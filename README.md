
# Cell Search Engine

A powerful QuPath extension that enables **advanced cell search, clustering, and phenotype highlighting** in high-resolution tissue images.  
Supports **Quick Search**, **Neighborhood Analysis**, **Multi-Query Search**, **Phenotype Highlighting**, and **CSV Cluster-Based Search**.

---

## 🛠️ Tools and Technologies

- **Java 11+**
- **Groovy**
- **QuPath** (tested with version `0.4.0`)
- **IntelliJ IDEA** (for development)
- **JavaFX** (for UI)
- **Apache Commons Math3** (for distance calculations)

---

## 🚀 Installation

1. **Clone the repository:**

    ```bash
    git clone https://github.com/saikiran888/Cell_Search_Engine.git
    cd Cell_Search_Engine
    ```

2. **Build the extension using Gradle:**

    ```bash
    ./gradlew clean build
    ```

    After successful build, the `.jar` file will be generated at:

    ```
    build/libs/cell-search-engine-extension-1.0.0.jar
    ```

3. **Install into QuPath:**

    - Open QuPath.
    - **Drag and drop** the generated `.jar` file into QuPath.
    - Restart QuPath.
    - You should now see the extension listed under:
      ```
      Extensions > Cell Search Engine
      ```

---

## 📖 How to Use

After installation, under `Extensions > Cell Search Engine`, you will see:

- **Quick Cell Search**
  - **Unified Search**:
    - Select a single cell ➔ Perform **Neighborhood Search**.
    - Select multiple cells ➔ Perform **Multi-Query Search** (Union, Intersection, Subtract, Contrastive, Competitive Boost).

- **Comprehensive Search**
  - **Community Search (CSV Cluster Search)**:
    - Upload a CSV containing cluster labels.
    - Highlight cells matching specific clusters.
  - **Phenotype Finder**:
    - Upload a CSV containing phenotype labels.
    - Highlight specific phenotypes like immune cells, tumor cells, etc.
  - **Reset Region Highlights**:
    - Reset and clear highlights in selected regions.

---

## 🧠 Core Functionalities

| Feature | Description |
|:---|:---|
| **Neighborhood Search** | Find cells spatially similar to a target cell based on marker expressions and morphology within a specified radius. |
| **Multi-Query Search** | Perform advanced cell search operations such as Union, Intersection, Subtraction, Contrastive Analysis, and Competitive Boost between multiple selected cells. |
| **Phenotype Finder** | Highlight cells based on precomputed phenotypic classifications from a CSV file (e.g., T cells, B cells, Macrophages). |
| **CSV Cluster Search** | Match and highlight cells based on external clustering results (e.g., Infomap clusters) provided in a CSV. |
| **Local Density Feature** | Compute and use local cell density as an additional feature for neighborhood search. |
| **Export Matched Cells** | Save coordinates of matched cells into a CSV file directly. |
| **Reset Highlights** | Quickly reset highlights for cells within an annotated region. |

---

## 🧩 Unified Search Modes

Depending on what you select:

- **Single Cell Selected** ➔ Runs **Neighborhood Search**.
- **Multiple Cells Selected** ➔ Runs **Multi-Query Search** with your chosen operation mode:
  - Union
  - Intersection
  - Subtract
  - Contrastive
  - Competitive Boost

---

## 📂 Folder Structure

```bash
Cell_Search_Engine/
├── src/
│   └── main/
│       └── groovy/
│           └── qupath/ext/template/DemoGroovyExtension.groovy
├── README.md
├── build.gradle
└── build/
    └── libs/
        └── cell-search-engine-extension-1.0.0.jar
```

---

## 🙋‍♂️ Author

**Saikiran Mandula**  
[GitHub](https://github.com/saikiran888) | [Portfolio](https://saikiranmandula.vercel.app/)

---




---
  
✅ **Now you can paste this entire thing directly into your GitHub repo `README.md` file.**  

Would you also like me to prepare a short 1-line project description you can use on the GitHub homepage ("About" section)?  
It will make the repo homepage even more polished! 🎯  
Want me to give you that too? 🚀
